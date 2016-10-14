package bos.copybook.parse

import com.ibm.jzos.fields.BigDecimalAccessor
import com.ibm.jzos.fields.BigIntegerAccessor
import com.ibm.jzos.fields.Field
import com.ibm.jzos.fields.IntAccessor
import com.ibm.jzos.fields.LongAccessor
import com.ibm.jzos.fields.StringField

class ReadWriteFactory {
	/**
	 * Each field processor has a different method for reading and writing (getString, getInt, etc...) so a
	 * a generic method reference (read/write) is stored for each variable to simplify processing later.
	 *
	 * @param variable updated with the read/write methods for manipulating the data
	 * @param field object which can read/write data whoses methods are being mapped to the variable definition
	 */
	public void generateReaderAndWriter(def variable, Field field) {
		if (field) {
			switch (field) {
				case StringField:
					variable.read = field.&getString
					variable.write = field.&putString
					break
					
				case IntAccessor:
					variable.read = field.&getInt
					variable.write = { value, bytes, offset ->
						if (value instanceof String && value.isInteger()) {
							field.putInt(value.toInteger(), bytes, offset)
						} else if (value instanceof BigDecimal) {
							field.putInt(value.intValue(), bytes, offset)
						} else {
							field.putInt(value, bytes, offset)
						}
					}
					break
					
				case BigDecimalAccessor:
					variable.read = field.&getBigDecimal
					variable.write = field.&putBigDecimal
					break
					
				case LongAccessor:
					variable.read = field.&getLong
					variable.write = { value, bytes, offset ->
						if (value instanceof String && value.isLong()) {
							field.putLong(value.toLong(), bytes, offset)
						} else if (value instanceof BigDecimal) {
							field.putLong(value.longValue(), bytes, offset)
						} else {
							field.putLong(value, bytes, offset)
						}
					}
					break
				case BigIntegerAccessor:
					variable.read = field.&getBigInteger
					variable.write = field.&putBigInteger
					break
				case OccurrenceField:
					//no mapping required
					break
				default:
					throw new IllegalStateException("Don't know how to handle $field for $variable")
			}
		}
	}
}
