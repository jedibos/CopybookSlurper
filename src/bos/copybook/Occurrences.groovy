package bos.copybook


/**
 * Stores the occurrences of data in a group. Contains instructions to read and write data within an occurrence
 * along with the data itself. 
 */
class Occurrences extends ArrayList {
	def List readers
	def List writers
	def byte [] bytes
		
	/**
	 * Constructor.
	 * 
	 * <p>
	 * Stores off the readers, writers, and bytes for reference as needed. The list is initialized with an empty
	 * object array of the proper size (based on the number of occurrences) to prevent any array out of bounds
	 * problems when referencing the data. 
	 *
	 * @param readers list of instructions on how to read the data in the occurrences
	 * @param writers list of instructions on how to write the data in the occurrences
	 * @param bytes the bytes representing the commarea being mapped or written to 
	 */
	public Occurrences(List pReaders, List pWriters, byte [] pBytes) {
		//initialize the array to the proper size, contains empty objects
		super([null] * pReaders.size())
		
		//store of the readers, writers, and the byte array for use when the get/put methods are invoked
		readers = pReaders
		writers = pWriters
		bytes = pBytes
	}

	/**
	 * Retrieves data, occurrences, or a copybook for a particular occurrence. The readers and writers use
	 * the index to determine what offset in the byte array of where to start reading or writing. 
	 *
	 * @param index defines which occurrences to put the data into
	 * @return the value from the byte array at that position in the occurrences
	 */
	public Object getAt(int index) {
		def value = super.get(index)										//store data 
		
		if (!value) {
			def readIt = readers[index]
			if (readIt instanceof List) {
				value = new Occurrences(readIt, writers[index], bytes)		//nested occurrence clauses
			} else if (readIt instanceof Map) {
				value = new Copybook(readIt, writers[index], bytes)			//multiple variables in group
			} else {
				value = readIt(bytes)										//PIC and occurs clause
			}
			
			super.set(index, value)
		}
		return value
	}
	
	/**
	 * Writes data to an instance of an occurrence. Should only be invoked on elements with a picture
	 * clause, does not support writing group moves.
	 *  
	 * <p>
	 * Example
	 * <pre>
	 * def slurper = new CopybookSlurper('''\
	 * 01 TOP_LEVEL.
	 *	  03 STATE PIC XX OCCURS 2 TIMES. ''')
	 *
	 * def copybook = slurper.getCopybook()
	 * copybook.STATE[0] = 'MI' // this invokes putAt to put 'MI' into the byte stream
	 * </pre>
	 * 
	 * @param index defines which occurrences to put the data into
	 * @param value data being written to the byte stream 
	 */
	public void putAt(int index, Object value) {
		def writeIt = writers[index]
		if (!(writeIt instanceof Closure)) {
			throw new IllegalArgumentException(
				"Cannot set object with occurrences at $index to $value, this is a group field")
		}
		writeIt(value, bytes)		//write to byte array
		super.set(index, value)		//write to the actual list
	} 

	/**
	 * Delegates to {@link #putAt}.
	 * @param index identifies which variable in the list to retrieve
	 * @param value data being written to the byte stream 
	 */
	public void put(int index, Object value) {
		putAt(index, value)
	}
		
	/**
	 * Delegates to {@link #getAt}.
	 * @param index identifies which variable in the list to retrieve
	 * @return map for the data variables contained within the group
	 */
	public Object get(int index) {
		return getAt(index)
	}
	
	/**
	 * Invokes the reader for each instance to ensure the iterator will have data for each element.
	 * @return iterator with all of the data from the list 
	 */
	@Override
	public Iterator iterator() {
		for (int i = 0; i < this.size(); i++) {
			this.get(i)
		}
		return super.iterator()
	}
	
}
