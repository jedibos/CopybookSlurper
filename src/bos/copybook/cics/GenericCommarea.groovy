package bos.copybook.cics

import groovy.transform.Canonical

import javax.resource.cci.Record
import javax.resource.cci.Streamable

/**
 * Generic CICS commarea used to pass a byte array to/from CICS. 
 */
class GenericCommarea implements Record, Streamable {

	/**
	 * Getter/Setter methods required by the {@link Record} interface.
	 */
	String recordName = this.getClass().getName()
	
	/**
	 * Getter/Setter methods required by the {@link Record} interface.
	 */
	String recordShortDescription = this.getClass().getName()

	/**
	 * The data to pass to/from CICS
	 */
	byte[] bytes
	
	/**
	 * The length of the byte stream.
	 */
	final int size
	
	/**
	 * Constructor.
	 * 
	 * @param pSize the size of the commarea
	 * @param pBytes the data being sent to/from CICS
	 */
	public GenericCommarea(byte[] pBytes) {
		size = pBytes.length
		bytes = pBytes
	}

	/**
	 * Retrieves all of the data returned from CICS. 		
	 */
	@Override
	public void read(java.io.InputStream inputStream) throws java.io.IOException {
		final byte[] input = new byte[inputStream.available()]
		inputStream.read(input)
		bytes = input
	}

	/**
	 * Pushes all of the input data to CICS.
	 */
	@Override
	public void write(java.io.OutputStream outputStream) throws java.io.IOException {
		outputStream.write(this.bytes, 0, this.size)
	}

	/**
	 * Required by {@link Record} to expose clone as a public method.
	 */
	//CHECKSTYLE_OFF: NoCloneCheck
	@Override
	public Object clone() throws CloneNotSupportedException {
		return super.clone()
	}
}

