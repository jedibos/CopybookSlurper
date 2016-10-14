package bos.copybook.parse


import java.util.Map

import groovy.transform.ToString

import com.ibm.jzos.fields.Field

/**
 * Stores variable information for fields defined with occurrences.
 * 
 * <p>
 * This could be either a group definition with nested variables, in which case the 'fieldProcessors' variable
 * will be filled with all of the variables contained within the group. Or it could a variable that defines both
 * a picture clause and an occurrence clause, in which case the Field, read, and write variables will be filled
 * which information about the particular field being processed. 
 */
@ToString
class OccurrenceField implements Field {
	/**
	 * Where the start of the group can be found within the main byte stream.
	 */
	int offset

	/**
	 * The total number of bytes used by the variables in this occurrence group.
	 */
	int byteLength
	
	/**
	 * The number of occurrences.
	 */
	int occurrences
	
	/**
	 * The data variables contained within this data group.
	 */
	List<DataVariable> fieldProcessors = []
		
	/**
	 * The length of each occurrence. This value is calculated lazily because as children are added to the
	 * object, the byteLength will increase. The first reference to this variable should be after all children
	 * have been added.
	 */
	@Lazy
	int occurrenceLength = byteLength / occurrences

	/**
	 * In situations where a variable with a PIC clause also has OCCURRENCES, this will provide the type of field
	 * that is being repeated.
	 */
	Field field
	
	/**
	 * In situations where a variable with a PIC clause also has OCCURRENCES, this will provide the read method
	 * to use for the particular field type.
	 */
	def read

	/**
	 * In situations where a variable with a PIC clause also has OCCURRENCES, this will provide the write method
	 * to use for the particular field type.
	 */
	def write

}
