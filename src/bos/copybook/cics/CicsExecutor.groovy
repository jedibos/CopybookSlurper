package bos.copybook.cics

import javax.resource.cci.Connection
import javax.resource.cci.ConnectionFactory
import javax.resource.cci.Interaction

import com.ibm.connector2.cics.ECIInteractionSpec

/**
 * Service to invoke CICS modules.
 */
class CicsExecutor {
	/**
	 * The connection to CICS.
	 */
	ConnectionFactory connectionFactory
	
	/**
	 * The transaction id to use when invoking CICS.
	 */
	String transId
	
	//----------------------- Constructors
	
	/**
	 * Constructor.
	 * @param pFactory used to connect to CICS
	 * @param pTransId identifies the transaction id to use when calling CICS
	 */
	public CicsExecutor(ConnectionFactory pFactory, String pTransId) {
		connectionFactory = pFactory
		transId = pTransId
	}
	
	//----------------------- Public Methods
	
	/**
	 * @param module identifies the CICS module to invoke 
	 * @param input bytes of data that should be sent to CICS as a commarea
	 * 
	 * @return the bytes sent back from CICS
	 */
	public byte[] callCics(String module, byte[] input) {
		Connection connection = connectionFactory.getConnection()
		Interaction interaction = connection.createInteraction()
		try {
			final GenericCommarea outputCommarea = new GenericCommarea(input)
			
			interaction.execute(
				new ECIInteractionSpec(functionName: module, TPNName: transId),
				new GenericCommarea(input),
				outputCommarea)
			return outputCommarea.bytes
		} finally {
			interaction.close()
		}
	}
}
