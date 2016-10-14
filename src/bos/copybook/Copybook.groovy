package bos.copybook

/**
 * Contains the data mapping for variables in a COBOL group data structure. This is used both for the main
 * data structure, and for any sub group occurrences.
 */
class Copybook extends HashMap {
	final Map reader
	final Map writer
	byte [] bytes
	
	//----------------------- Constructor
	
	/**
	 * Constructor.
	 * 
	 * @param pReader maps data variable names to closures that show how to parse data from the byte array
	 * @param pWriter maps data variable names to closures that show how to write data to the byte array
	 * @param pBytes the data being parsed or populated
	 */
	public Copybook(Map pReader, Map pWriter, byte [] pBytes) {
		reader = pReader
		writer = pWriter
		bytes = pBytes
	}

	
	//----------------------- Public Methods
	
	/**
	 * @return the string representation of the bytes in the copybook
	 */
	public String getDataString() {
		return new String(bytes, Constants.ENCODING)
	}
	
	/**
	 * Uses reader to pull data or mapping for a particular variable.
	 * @param index defines which occurrences to put the data into
	 * @return the value from the byte array at that position in the occurrences
	 */
	public Object get(Object pKey) {
		def key = sanitizeKey(pKey)
		def value = super.get(key)
		if (!value) {
			def readIt = reader.get(key) //the reader knows how to process the byte array
			if (readIt) {
				if (readIt instanceof List) {
					value = new Occurrences(readIt, writer.get(key), bytes)		//occurrence list
				} else {
					value = readIt(bytes)										//individual variables
				}
				super.put(key, value) //store the data or instructions in the map for subsequent reads
			} else {
				throw new IllegalArgumentException("'$key' is not a valid property of this copylib")
			}
		}
		
		return value
	}
	
	/**
	 * Uses the writer to push data into the byte array.
	 * 
	 * <p>
	 * If the writer for the key was a list, that means the program attempted to put data into a group
	 * occurrence field. For example, given the following copylib definition. 
	 * <pre>
	 * 01 GROUPED-DATA OCCURS 2 TIMES.
	 *    03 DATA-1 PIC X.
	 *    03 DATA-2 PIC X.
	 * <pre>
	 * it is not allowed to call <code>GROUPED_DATA[1] = '12'</code>, you can't put data into a group, instead
	 * the fields need to be referenced individually
	 * <code>GROUPED_DATA[1].DATA-1 = '1'; GROUPED_DATA[1].DATA-1 = '2'</code>.
	 *
	 * @param key identifies the data variable
	 * @param value the value to be written to the byte stream
	 */
	@Override
	public Object put(Object pKey, Object value) {
		def key = sanitizeKey(pKey)
		try {
			def writeIt = writer.get(key) //intentionally using 'super' to bypass closure processing
			if (writeIt) {
				if (writeIt instanceof List) {
					throw new IllegalArgumentException("Cannot set $key to $value, $key is defined with occurrences")
				} else {
					writeIt(value, bytes)		//write the data to the byte array
				} 
			} else {
			    throw new IllegalArgumentException("Cannot set $key to $value, $key is not defined in the data structure")
			}
		} catch (MissingMethodException e) {
			throw new IllegalArgumentException("Could not set $key with ${value.class.name} : $value", e)
		}
	}
	
	/** 
	 * The reader will contain all of the keys that are contained in the copylib, so delegate to it.
	 */
	@Override
	public Set keySet() {
		return reader.keySet()
	}
	
	/**
	 * Ensures that if the entrySet is requested, all of the actual values in the entry set will be returned,
	 * rather than caching closures. By going through all of the reader's keys, all of the data for the copylib
	 * will be loaded.
	 */
	@Override
	public Set entrySet() {
		def entries = reader.keySet().each { this.get(it) } 
		return super.entrySet()
	}
	
	/**
	 * Ensures that if the valueSet is requested, all of the actual values in the entry set will be returned,
	 * rather than caching closures.
	 */
	@Override
	public Collection values() {
		return this.entrySet().collect { it.value }
	}	
	
	/**
	 * Invokes the {@link #entrySet} method to ensure that all the data is parsed before display the contents
	 * of the map, otherwise there may be data in the byte array that hasn't been accessed yet that would not
	 * display.
	 */
	@Override
	public String toString() {
		return this.entrySet().toString()
	}
	
	//----------------------- Protected Methods
	
	/**
	 * @param pKey the key to be retrieved from the map
	 * @return key with all dashes replaced as underscores, convenience to handle either format, underscores are
	 * 		better handled in Java than dashes
	 */
	protected Object sanitizeKey(String pKey) {
		return pKey.replaceAll('-', '_')
	}
}
