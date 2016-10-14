package bos.copybook

import java.util.regex.Matcher

import javax.resource.cci.ConnectionFactory

import bos.copybook.cics.CicsExecutor
import bos.copybook.parse.CopybookParser
import bos.copybook.parse.DataVariable
import bos.copybook.parse.OccurrenceField
import bos.copybook.parse.ReadWriteFactory

import com.ibm.jzos.fields.BigDecimalAccessor
import com.ibm.jzos.fields.BigIntegerAccessor
import com.ibm.jzos.fields.CobolDatatypeFactory
import com.ibm.jzos.fields.IntAccessor
import com.ibm.jzos.fields.LongAccessor


/**
 * Creates maps of data representing a COBOL copybook. Behind the scenes it uses the IBM JZOS library to
 * write/parse bytes into Java objects. The slurper <b>should be created once</b> since it does a lot of
 * heavy lifting by parsing through the copybook definition. The {@link #getCopybook()} and 
 * {@link #callCics(String)} methods can be used repeatedly.
 *
 * <h3>Read Examples</h3>
 * <pre>
 * def slurper = new CopybookSlurper("""
 * 01 EXAMPLE.
 *    03  STATES OCCURS 2 TIMES.
 *        04 STATE_NAME    PIC X(10).
 * """)
 * def reader = slurper.getCopybook(byteStream)
 * reader.STATES[1].STATE_NAME
 * </pre>
 *
 * Or a more complicated scenario.
 * <pre>
 * def slurper = new CopybookSlurper('''\
 * 01 TESTING-COPYBOOK.
 *    03  TEST-NAME                         PIC X(10).
 *    03  STATES OCCURS 2 TIMES .
 *        05  STATE-ABBR                    PIC XX.
 *        05  STATE-NAMES OCCURS 2 TIMES.
 *            08  STATE-NUM                 PIC 99.
 *            08  STATE-NAME                PIC X(10).
 *        05  STATE-RANKING                 PIC 9.
 * 03  theEnd                               PIC XXX.''')
 * def reader = slurper.parse('STATETEST MI01MICHIGAN  02MICH      1OH01OHIO      02          2END'.getBytes('IBM-37'))
 *
 * println results.STATES[1].STATE_ABBR
 * //output: 'OH'
 *
 * results.STATES[0].each {
 *   println it.STATE_NUM + '-' + it.STATE_NAME
 * }
 * //output: 1-MICHIGAN
 *           2-OHIO
 *
 * println results.toString() // the toString call is method at times with Groovy...
 * //output, normally a single line
 * [STATES:[[STATE_RANKING:1, STATE_NAMES:[[STATE_NAME:MICHIGAN, STATE_NUM:1],
 *                                         [STATE_NAME:MICH, STATE_NUM:2]], STATE_ABBR:MI],
 *          [STATE_RANKING:2, STATE_NAMES:[[STATE_NAME:OHIO, STATE_NUM:1],
 *                                         [STATE_NAME:, STATE_NUM:2]], STATE_ABBR:OH]],
 *  TEST_NAME:STATETEST, theEnd:YES]
 * </pre>
 *
 * <h3>Write Examples</h3>
 * <pre>
 * def slurper = new CopybookSlurper("""
 * 01 TOP-LEVEL.
 *    03 STATES OCCURS 2 TIMES.
 *       05 STATE-NUM        PIC X(3).
 *       05 STATE-NAME       PIC X(10).
 *       05 NUMERIC-STATE    PIC 9(2).
 * """)
 *
 * def writer = slurper.getCopybook()
 * writer.STATES[0].STATE_NUM = '1'
 * writer.STATES[0].STATE_NAME = 'MICHIGAN'
 *
 * writer.with {
 * 	STATES[0].with {
 * 	  NUMERIC_STATE = 2
 * 	}
 * 	STATES[1].with { STATE_NUM = '2'; STATE_NAME = 'OHIO'; NUMERIC_STATE = 3 }  
 * }
 * println "'" + new String(writer.getBytes(), 'IBM-37') + "'"
 *
 * //output: '1  MICHIGAN  022  OHIO      03'
 * </pre>
 */
class CopybookSlurper {
	//----------------------- Instance Variables

	/**
	 * Service to parse the copylib and retrieve variable names, formats, occurrences, values, etc...
	 */
	private CopybookParser parser = new CopybookParser()

	/**
	 * Factory to update data variables with read/write instructions.
	 */
	private ReadWriteFactory readWriteFactory = new ReadWriteFactory()
	
	/**
	 * Service to invoke CICS.
	 */
	def CicsExecutor cicsExecutor
	
	/**
	 * Contains instructions for each variable on how to read data from a byte array.
	 */
	private Map reader = [:]
	
	/**
	 * Contains instructions for each variable on how to write data to a byte array.
	 */
	private Map writer = [:]

	/**	
	 * Contains any variables that have default values defined.
	 */
	private List defaults = []
	
	/**
	 * Lazy invocation which generates a byte array with all of the default values loaded into the byte array. 
	 * A copy of this byte array can then be used in the constructor to provide a byte array for a new copybook
	 * instance. 
	 */
	@Lazy
	private byte[] defaultBytes = {
		def bytes = new byte[length] as byte[]
		defaults.each { defaultWriter -> defaultWriter(bytes) }
		return bytes
	}()
	
	/**
	 * The total length of the copylib.
	 */
	def int length

		
	//----------------------- Constructors
	
	/**
	 * Parses a copybook and creates a set of instructions on how to read/write data to a byte array for working
	 * with COBOL formatted data. Can be used to read/write batch files, or to process CICS commareas.
	 *
	 * @param pDataDefinitions string (usually multiple line) with a copybook format that should be parsed
	 */
	public CopybookSlurper(String pDataDefinitions) {
		//process through the copylib to capture all of variables, the string is split on a period at the end of
		//the line (allowing for trailing spaces). All tabs, new lines, and extra spaces are removed to simplify
		//the parsing of the formats.
		List<DataVariable> variables = parser.parseDefinitions(
			pDataDefinitions.split(/\.\s*\n/).collect { String str ->
				return str.replaceAll('[\n\t]', ' ').replaceAll('\\s\\s+', ' ').trim() })
		
		//generate instructions on how to process this copylib for each variable
		List<DataVariable> fieldProcessors = generateFieldProcessors(variables)

		//get the total length of all variables (ignore redefines)
		length = fieldProcessors.iterator().sum { it.redefines || it.redefinesGroup ? 0 : it.length }
		
		//construct an instruction set using maps, lists, and closures on how the data should be processed
		def prepared = prepareReadersAndWriters(fieldProcessors)
		reader = prepared.reader
		writer = prepared.writer
	}
	
	/**
	 * Constructor.
	 *
	 * @param pFactory connection to CICS
	 * @param pDataDefinitions string (usually multiple line) with a copybook format that should be parsed
	 */
	public CopybookSlurper(String dataDefinitions, ConnectionFactory pFactory, String transId) {
		this(dataDefinitions)
		cicsExecutor = new CicsExecutor(pFactory, transId)
	}


	//----------------------- Public Methods

	/**
	 * Retrieves a mapping object (copybook) used to read/write data. Default values (based on
	 * the VALUE clause) will be loaded if no byte array is passed in.
	 * 
	 * @param pBytes optional parameter to use with the returned copybook, if no value is provided, a new byte
	 * 	array matching the size of the copybook will be generated
	 * @return object which can be used to read/write named properties from the copybook
	 */
	public Copybook getCopybook(byte [] pBytes = null) {
		byte [] bytes = pBytes
		if (bytes == null) {
			bytes = new byte[length] as byte[]
			if (defaults) {
				System.arraycopy(defaultBytes, 0, bytes, 0, length)
			}
		}
		return new Copybook(reader, writer, bytes)
	}
	
	/**
	 * Calls CICS for a given module. Generates a new copybook using the {@link #getWriter()} method which can
	 * then be updated with parameter information within the closure.
	 * 
	 * <p>
	 * Example
	 * <pre>
	 * def userInfo = SLURPER.callCics('MODULE') { Copybook input ->
	 *   input.'NAME' = 'JOHN SMITH'
	 * }
	 * println userInfo.'DEPT'
	 * </pre>
	 *   
	 * @param module identifies the CICS module to invoke
	 * @param input copybook of data to be used as input
	 * @param closure instructions on how to update the input commarea
	 * @return the return copybook which can be used to access individual properties
	 */
	public Copybook callCics(String module, Copybook input = null, Closure closure = null) {
		Copybook data = input ?: getCopybook()
		closure?.call(data)
		return getCopybook(cicsExecutor.callCics(module, data.getBytes()))
	}
	
	
	//----------------------- Protected Methods

	/**
	 * Once all of the data definitions have been parsed into DataVariable objects, the appropriate read, write,
	 * and group processing functions must be stored for each variable. Also, the offset within the byte stream
	 * must be determine for each variable so that the readers/writers know what part of the data to operate
	 * against.
	 *
	 * <p>
	 * The list that is returned will only contain the {@link DataVariables} for the top level variables in a
	 * copylib. Any variables that are part of an occurrence group will be stored within that group's object, not
	 * in the main list. Also, variables which do not affect the parsing, such as group definitions will be 
	 * ignored in the final list.
	 *
	 * <p>
	 * The IBM CobolDatatypeFactory class stores a running offset value for each the variables as they are
	 * created, however, group processing requires a little additional work to update the offsets for group
	 * definitions. Each group will also have it's own CobolDatatypeFactory to define offsets within the sub
	 * section of the main byte array.
	 *
	 * @param copylibVariables 
	 * @return a list of data variables with defined read/write methods and offset information to know how the
	 * 		data in the byte array should be processed for each variable
	 */
	protected List<DataVariable> generateFieldProcessors(List<DataVariable> copylibVariables) {
		CobolDatatypeFactory rootFactory = parser.generateDataTypeFactory()
		
		List<DataVariable> fieldProcessors = []
		
		//generate processors for each variable
		copylibVariables.each { DataVariable variable ->
			DataVariable parent = getParent(copylibVariables, variable)
			CobolDatatypeFactory factory = parent?.dataTypeFactory ?: rootFactory
			variable.offset = factory.offset

			/*
			 * Handle for PIC clauses that also have occurrences, needs to strip the occurrences clause from the
			 * format so the field type can be determined, but the occurrences also needs to be set aside so the
			 * processing for these records can be handled correctly when trying to use index map notation to
			 * reference them (ie STATE[0] = 'MI').
			 */
			if (variable.format =~ /OCCURS\s+(\d+)\s+TIMES/) {
				variable.occurrences = (Matcher.lastMatcher[0][1].trim()).toInteger()
				variable.format = (variable.format.replace(Matcher.lastMatcher[0][0], '')).trim()
			}

			/*
			 * Handle for PIC clauses that have a value defined. The value clause will be stripped from the
			 * format so the field type can be processed correctly. The initial value will be set in the variable
			 * when a copylib instance is created for a byte stream.
			 */
			if (variable.format =~ /VALUE (.+)/) {
				variable.initValue = Matcher.lastMatcher[0][1].trim()
				variable.format = (variable.format.replace(Matcher.lastMatcher[0][0], '')).trim()
			}

			// The only affect of the redefines clause is to reset the offset in the parent data type factory.
			if (variable.redefines) {
				variable.offset = copylibVariables.find { it.name == variable.redefines }.offset
				factory.offset = variable.offset
			}
						
			/*
			 * For all variables that have a format, we need to create a field parser and update the offset. If
			 * the variable has parent(s), their lengths and offsets need to updated as well.
			 */
			if (variable.format && !variable.occurrences) {
				variable.field = parser.determineFieldType(factory, variable.format)
				if (variable.field) {
					variable.length = variable.field.byteLength //copy the length from the field to the variable
				}

				if (parent) {
					updateParentAttributes(rootFactory, copylibVariables, parent, variable.length)
					((OccurrenceField) parent.field).fieldProcessors << variable
				} else {
					fieldProcessors << variable
				}

			/*
			 * All fields that have OCCURS clauses needs to be stored off for reference when the children
			 * are being processed. Each of these fields has it's own data type factory which keeps track of the
			 * offset within the occurrence group.
			 */
			} else if (variable.occurrences) {
				//defines this group
				OccurrenceField occurrencesField = new OccurrenceField(offset: variable.offset, occurrences: variable.occurrences)
				variable.field = occurrencesField
				
				//add this group to any parent structures				
				if (parent) {
					((OccurrenceField) parent.field).fieldProcessors << variable
				} else {
					fieldProcessors << variable
				}
				
				//prepare data type factory specific for parsing through the group information
				variable.dataTypeFactory = parser.generateDataTypeFactory()
				
				/*
				 * Fields with both a PIC and occurrence clause needs special handling, a field type is
				 * generated and stored with the OccurrenceField object, then the read/write methods for the 
				 * field are also stored in the OccurrenceField so that it can parse the data contained within
				 * the group.  
				 */
				if (variable.format) {
					occurrencesField.field = parser.determineFieldType(variable.dataTypeFactory, variable.format)
					
					//copy length data to itself as an occurrence
					updateParentAttributes(rootFactory, copylibVariables, variable, occurrencesField.field.byteLength)
					 
					readWriteFactory.generateReaderAndWriter(occurrencesField, occurrencesField.field)
				}
			}
			
			//define read/write methods for this variable
			readWriteFactory.generateReaderAndWriter(variable, variable.field)
		}

		return fieldProcessors
	}

	/**
	 * When working with nested occurrence variables, the length and offset variables must be updated not only
	 * in this object, but all of the parents up the chain to the main data factory. So the method is called
	 * recursively as long as there are parents to update.
	 *
	 * @param rootFactory the data type factory for the top level of the copylib
	 * @param copylibVariables list of variables, used to move recursively up the parent tree
	 * @param parent the parent whose length/offset is being updated
	 * @param length the length of the field that should affect the parent's length
	 */
	protected void updateParentAttributes(
		CobolDatatypeFactory rootFactory, List<DataVariable> copylibVariables, DataVariable parent, int length) {

		//determine the total length for this variable
		int lengthOfOccurrences = length * parent.occurrences
		
		//update the length attributes in both the variable definition and the field processor
		parent.length += lengthOfOccurrences
		parent.field.byteLength = parent.length

		if (parent.parentGroup) {
			DataVariable grandParent = getParent(copylibVariables, parent)

			//the data type factory contains a pointer relative to the byte stream for each variable to know
			//where that variables data starts, so it must be updated so that subsequent variables know where
			//there data begins. Each occurrence group has it's own data type factory which is relative to the
			//data within the group 
			grandParent.dataTypeFactory.incrementOffset(lengthOfOccurrences)
			
			//recursively update the parents, grandparents, etc.. with new length information
			updateParentAttributes(rootFactory, copylibVariables, grandParent, lengthOfOccurrences)
		} else {
			//the grand-daddy of them all
			rootFactory.incrementOffset(lengthOfOccurrences)
		}
	}

	/**
	 * @param variable the variable who's parent is being retrieved.
	 * @return the parent object for variables part of an occurrence group, or null if no
	 * 		parent was defined, or found
	 */
	protected DataVariable getParent(List<DataVariable> copylibVariables, DataVariable variable) {
		if (variable.parentGroup) {
			return copylibVariables.find { it.name == variable.parentGroup }
		}
	}
	
	/**
	 * Creates a section of instructions on for the copylibs read/write functions to map data.
	 * 
	 * <p>
	 * The reader/writer methods for all variables, including occurrence groups are loaded into a combination
	 * of maps and lists. These methods are passed into instances of {@link Copybook} and
	 * {@link GroupOccurrences) objects to specify how variables are to be read/written to the byte array. When
	 * there are nested structures, the {@link Copybook#get(String)} method will handle creating new instances
	 * of {@link Copybook} objects passes along the nested set of instructions. 
	 * 	 
	 * @param fieldProcessors all the instructions for a field, or the top-level data structure
	 * @param offset determines the offset within the byte array where the data for a field resides, this value
	 * 		will be zero for normal variables, hence the default option, however, when working with groups of
	 * 		data, each occurrence is offset, and if you have nested occurrences, the offsets need to stack on
	 * 		one another.
	 * @return
	 */
	protected Map<String, Object> prepareReadersAndWriters(List<DataVariable> fieldProcessors, int offset = 0) {
		Map localReader = [:]
		Map localWriter = [:]
		
		fieldProcessors.each { DataVariable variable ->
			if (variable.field instanceof OccurrenceField) {
				localReader[variable.name] = []
				localWriter[variable.name] = []
				
				OccurrenceField group = (OccurrenceField) variable.field
				group.occurrences.times { index ->
					int varOffset = group.offset + offset + (group.occurrenceLength * index)
					//group offset 				-> start location relative to the parent group
					//offset 					-> the offset of the parent
					//occurrenceLength * index 	-> offset for each occurrence within the group
					
					if (group.field) {
						//handles PIC ... OCCURS ... scenarios, the read/write methods for the field are stored  
						localReader[variable.name] << { bytes -> group.read(bytes, varOffset) }
						
						def writeIt = { value, bytes -> group.write(value, bytes, varOffset) }
						localWriter[variable.name] << writeIt
				
						//prepares a closure to write a default value to the copylib for initialization
						if (variable.initValue) {
							def value = getSanitizedInitValue(variable)
							defaults << { bytes -> writeIt(value, bytes) }
						}						
					} else {
						//recursive call to create readers/writes for all variables contained within this occurrence 
						Map prepared = prepareReadersAndWriters(group.fieldProcessors, varOffset)
						localReader[variable.name] << prepared.reader
						localWriter[variable.name] << prepared.writer
					}
				}
			} else {
				def writeIt = { value, bytes -> variable.write(value, bytes, offset) }

				//load the reader/writer closures for the variable				
				localReader[variable.name] = { bytes -> variable.read(bytes, offset) }
				localWriter[variable.name] = writeIt
				
				//prepares a closure to write a default value to the copylib for initialization
				if (variable.initValue) {
					def value = getSanitizedInitValue(variable)
					defaults << { bytes -> writeIt(value, bytes) } 
				}
			}
		}
		
		return ['reader': localReader, 'writer': localWriter]
	}
	
	/**
	 * Translates the value from the VALUE clause into an object that can be used to set the Java object.
	 * Removes any wrapping 's and translates SPACE(S) and ZERO(S) into those values. Integer values and
	 * BigDecimal amounts are translated as well. 
	 *  
	 * @param value the VALUE clause value to translate.
	 * @return
	 */
	private Object getSanitizedInitValue(DataVariable variable) {
		String value = variable.initValue
		
		switch (value) {
			case ~'SPACE(S)?':
				return ''
			case ~'ZERO(S)?':
				return 0
			case ~/'(.+)'/:
				value = Matcher.lastMatcher[0][1]
		}
				
		if (variable.field instanceof IntAccessor) {
			return value.toInteger()
		} else if (variable.field instanceof BigDecimalAccessor) {
			return value.toBigDecimal()
		} else if (variable.field instanceof LongAccessor) {
			return value.toLong()
		} else if (variable.field instanceof BigIntegerAccessor) {
			return value.toBigInteger()
		}
		
		return value
	}
}
