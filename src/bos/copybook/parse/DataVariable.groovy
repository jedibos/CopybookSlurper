package bos.copybook.parse


import groovy.transform.ToString

import com.ibm.jzos.fields.CobolDatatypeFactory
import com.ibm.jzos.fields.Field

/**
 * Properties for data variables defined in a COBOL copybook.
 */
@ToString
class DataVariable {
	/** The level within the data structure. */
	int level

	/**
	 * The name of the variable. Any '-'s will be replaced by '_' to be more compatible with Java formatted
	 * variable names.
	 */
	String name

	/** The format, defined in the PICTURE clause for this variable. */
	String format

	/** The number of bytes that are used by this field. */
	int length

	/** The name of the variable that is being redefined by this variable. */
	String redefines

	/** The name of the variable that this variable is a part of redefining */
	String redefinesGroup

	/** The number of occurrences for a variable with the 'OCCURS' clause. */
	int occurrences

	/** The initial value of a variable */
	String initValue
	
	/** The name of the variable defining occurrences to which this variable belongs. */
	String parentGroup

	/**
	 * Generates classes which can read/write bytes in a COBOL copybook, also keeps track of a counter which
	 * determines the offset in a byte stream where the data should be read from.
	 */
	CobolDatatypeFactory dataTypeFactory

	/** Offset within the byte stream where this variable's data can be found. */
	int offset

	/** Stores the IBM class used to write or read data in a byte stream to match a copybook. */
	Field field

	/**
	 * Method from the field object to read data from a byte stream. The name of the method varies depending
	 * on the type of data being read (String, BigDecimal, Integer, etc..), so this method reference
	 * simplifies the logic to read data.
	 */
	def read

	/**
	 * Method from the field object to write data to a byte stream. The name of the method varies depending
	 * on the type of data being written (String, BigDecimal, Integer, etc..), so this method reference
	 * simplifies the logic to write data.
	 */
	def write
}
