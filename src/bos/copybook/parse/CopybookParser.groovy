package bos.copybook.parse

import java.util.regex.Matcher

import com.ibm.jzos.fields.CobolDatatypeFactory
import com.ibm.jzos.fields.Field

import bos.copybook.Constants;

/**
 * Parses through a COBOL copybook and creates objects from the JZOS library which can be used to process the
 * variables. Each variable's level, name, format, and whether it defines or belongs to a group, or redefines
 * an existing variable will all be stored in a list.
 */
class CopybookParser {
	/**
	 * Parses the copybook definition.
	 * @param dataDefinitions list of variables being processed 
	 */
	public List<DataVariable> parseDefinitions(List<String> dataDefinitions) {
		LinkedList<DataVariable> occurrenceGroups = new LinkedList()
		LinkedList<DataVariable> redefinesGroups = new LinkedList()

		return dataDefinitions.collect { String variableDefinition ->

			/* This regular expression finds variable definitions with a PICTURE clause. The expression has
			 * several groups, the first being the level, then the variable name, the picture clause, and finally
			 * the actual data format (i.e. 9V99).
			 *
			 * Example: 04 VARIABLE-NAME REDEFINES SOMETHING PIC 9V99 COMP-3.
			 * 
			 * Group definitions
			 * 1. level - 04
			 * 2. variable-name
			 * 3. REDEFINES SOMETHING
			 * 4. SOMETHING
			 * 5. the overall picture clause
			 * 6. 'TURE' to allow PIC or PICTURE
			 * 7. 9.99
			 */
			def parsed = variableDefinition =~ /(\d{1,2})\s+([\w\d-]+)(\s+REDEFINES\s+([\w\d-]+))?(\s+PIC(TURE)?(.*))?/
			DataVariable variable = new DataVariable()

			if (parsed) {
				variable.level = parsed[0][1].toInteger()
				variable.name = parsed[0][2].replaceAll('-', '_')

				// the data format portion of the picture clause
				if (parsed[0][7]) {
					variable.format = parsed[0][7]
				}
				
				// Check if the variable is a part of an occurrence or redefines group. 
				variable.parentGroup = checkGroupLevels(occurrenceGroups, variable)
				variable.redefinesGroup = checkGroupLevels(redefinesGroups, variable)

				/*
				 * If the variable has a redefines clause, the name of the variable that it is redefining will
				 * be added to the definition so that the offset can be set correctly once the field definitions
				 * are determined for each data variable. This needs to be after the 
				 */
				if (parsed[0][3]) {
					variable.redefines = parsed[0][4].replaceAll('-', '_')
						redefinesGroups.addFirst(variable)
				}
				
				/*
				 * Variables with a format (PIC clause), even if they have an occurrence, should not be treated
				 * like an occurrence group, if the variable has an 'occurs' clause, it will be added to the
				 * occurrence list so that subsequent variables levels can be checked to see if they are a part
				 * of this occurrence
				 */
				if (!variable.format) {
					def occurs = variableDefinition =~ /OCCURS\s+(\d+)/
					if (occurs) {
						variable.occurrences = occurs[0][1].toInteger()
						occurrenceGroups.addFirst(variable)
					}
				}

			}
			return variable
		}
	}
	
	/**
	 * Spins through the groups which have been defined above this variable to determine if the variable should
	 * be a part of one of the groups, or represents the end of a group. Based on level.
	 * @param groups list of open groups
	 * @param variable the variable being processed
	 */
	protected String checkGroupLevels(LinkedList<DataVariable> groups, DataVariable variable) {
		while (groups) {
			DataVariable group = groups.peek()
			if (variable.level > group.level) {
				return group.name
			}
			groups.removeFirst()
		}
	}
	
	/**
	 * Determines the specific Field object to use based on the format of the COBOL PICTURE clause.
	 *
	 * @param dataTypeFactory jzos factory used to generate a field, contains offset information
	 * @param format the value after the PIC clause, 'X(10)' for example
	 * @return a field instance which can be used to read or write data
	 */
	public Field determineFieldType(CobolDatatypeFactory dataTypeFactory, String format) {
		switch (format.trim()) {
			/*
			 * Process alpha numeric fields.
			 *
			 * The regex allows for any number of X (alpha numeric characters), B (blank or space), or A (alpha
			 * characters), followed by an optional number of digits. If the number of digits was defined, the
			 * string will be that size, otherwise the number of 'X', 'B', or 'A' characters will be the length
			 * of the string.
			 *
			 * Given 'X(10)', the groups are as follows
			 * 1 - 'X'
			 * 2 - '(10)'
			 * 3 - '10'
			 *
			 * Given 'XXX', the groups are as follows
			 * 1 - 'XXX'
			 *
			 * Therefore, if group #3 is present, it determines the length, otherwise, the length is determined
			 * by the size of group #1.
			 */
			case ~/([XBA]+)(\((\d+)\))?\.?/:
				List<String> groups = Matcher.lastMatcher[0]

				int size = groups[3]?.toInteger() ?: groups[1].size()
				return dataTypeFactory.getStringField(size)

			/*
			 * Process numeric fields.
			 *
			 * The group matches are easiest to understand with a few examples. The following show the groups,
			 * which are found for the given string.
			 *
			 * Given 'SV9(3) COMP-3':
			 *  1 - 'S'
			 *  2 - 'V'
			 *  3 - '9'
			 *  4 - '(3)'
			 *  5 - '(3'
			 *  6 - '3
			 * 12 - 'COMP-3'
			 * 13 - '-3'
			 *
			 * Given 'S999V9(3)':
			 *  1 - `S`
			 *  3 - `999`
			 *  7 - 'V9'
			 *  8 - '9'
			 *  9 - '(3)'
			 * 10 - '(3'
			 * 11 - '3'
			 *
			 * Given 'S9(10)V99':
			 *  1 - 'S'
			 *  3 - '9'
			 *  4 - '(10)'
			 *  5 - '(10'
			 *  6 - '10'
			 *  7 - 'V99'
			 *  8 - '99'
			 *
			 * Therefore:
			 *  - When group #2 is found, we know that there is no integer portion of the field, the definition
			 *    starts with a 'V'. The decimal size is found in either 6, if there was a (..) definition, or
			 *    in 3, if there were just a number of 9's.
			 *  - When group #2 is not found, then the integer portion of the field is defined in group #6,
			 *    if there is a (..) part of the definition, or in group #3 if there is just a number of 9's.
			 *    Likewise, the decimal portion is determined using group #11 or #8 depending on whether the
			 *    value was defined with (..) or just 9's.
			 *
			 * The integer and decimal sizes, along with wether the data was signed and/or binary determines
			 * which of the Field parsers that IBM provides in the JZOS library should be used.
			 */
			case ~/(S)?(V)?(9+)((\((\d+))\))?(V(9+))?((\((\d+))\))?(\s+COMP(-3)?)?\.?/:
				List<String> groups = Matcher.lastMatcher[0]

				int intSize
				int decimalSize

				if (groups[2]) {
					decimalSize = groups[6]?.toInteger() ?: groups[3].size()

				} else { //matches 9...
					intSize = groups[6]?.toInteger() ?: groups[3].size()
					decimalSize = groups[11]?.toInteger() ?: groups[8]?.size() ?: 0
				}

				boolean signed = (groups[1] != null)	//group for 'S'
				boolean binary = (groups[12] != null)	//group contains 'COMP'
				boolean packed = (groups[13] != null)   //group contains '-3'

				if (packed) {
					if (decimalSize) {
						return dataTypeFactory.getPackedDecimalAsBigDecimalField(intSize + decimalSize, decimalSize, signed)
					} else if (intSize <= 9) {
						return dataTypeFactory.getPackedDecimalAsIntField(intSize, signed)
					} else if (intSize <= 18) {
						return dataTypeFactory.getPackedDecimalAsLongField(intSize, signed)
					} else {
						return dataTypeFactory.getPackedDecimalAsBigIntegerField(intSize, signed)
					}
				} else if (binary) {
					if (decimalSize) {
						return dataTypeFactory.getBinaryAsBigDecimalField(intSize + decimalSize, decimalSize, signed)
					} else if (intSize <= 9) {
						return dataTypeFactory.getBinaryAsIntField(intSize, signed)
					} else if (intSize <= 18) {
						return dataTypeFactory.getBinaryAsLongField(intSize, signed)
					} else {
						return dataTypeFactory.getBinaryAsBigIntegerField(intSize, signed)
					}
				} else {
					if (decimalSize) {
						return dataTypeFactory.getExternalDecimalAsBigDecimalField(intSize + decimalSize, decimalSize, signed)
					} else if (intSize <= 9) {
						return dataTypeFactory.getExternalDecimalAsIntField(intSize, signed)
					} else if (intSize <= 18) {
						return dataTypeFactory.getExternalDecimalAsLongField(intSize, signed)
					}
					return dataTypeFactory.getExternalDecimalAsBigIntegerField(intSize, signed)
				}

			/*
			 * Process formatting fields, treated as strings.
			 *
			 * The regex accounts for many of the formatting strings used in COBOL: '+', '-', 'X', etc.., but
			 * because it allows for numerics ('9's), this special case is handled after it has been determined
			 * that the format is not a standard alphanumeric or numeric field.
			 */
			case ~$/[+-9XAB0Z/,\.]+/$:
				return dataTypeFactory.getStringField(format.size())

			default:
				throw new IllegalArgumentException("Could not determine the field type given the format '$format'")
		}
	}

	/**
	 * @param encoding the encoding to use when parsing data, defaults to EBCDIC, 'IBM-37'
	 * @return a newly generated COBOL data type factory, used to construct Field objects for parsing byte streams
	 */
	public generateDataTypeFactory() {
		return new CobolDatatypeFactory(stringEncoding: Constants.ENCODING, stringTrimDefault: true)
	}
}
