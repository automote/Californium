/*******************************************************************************
 * Copyright (c) 2012, Institute for Pervasive Computing, ETH Zurich.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * 
 * This file is part of the Californium (Cf) CoAP framework.
 ******************************************************************************/
package ch.ethz.inf.vs.californium.dtls;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import ch.ethz.inf.vs.californium.dtls.AlertMessage.AlertDescription;
import ch.ethz.inf.vs.californium.dtls.AlertMessage.AlertLevel;
import ch.ethz.inf.vs.californium.util.DatagramReader;
import ch.ethz.inf.vs.californium.util.DatagramWriter;

/**
 * The server MUST send a Certificate message whenever the agreed-upon key
 * exchange method uses certificates for authentication. This message will
 * always immediately follow the {@link ServerHello} message. For details see <a
 * href="http://tools.ietf.org/html/rfc5246#section-7.4.2">RFC 5246</a>.
 * 
 * @author Stefan Jucker
 * 
 */
public class CertificateMessage extends HandshakeMessage {

	// Logging ///////////////////////////////////////////////////////////

	private static final Logger LOG = Logger.getLogger(CertificateMessage.class.getName());

	// DTLS-specific constants ///////////////////////////////////////////
	
	/**
	 * <a href="http://tools.ietf.org/html/rfc5246#section-7.4.2">RFC 5246</a>:
	 * <code>opaque ASN.1Cert<1..2^24-1>;</code>
	 */
	private static final int CERTIFICATE_LENGTH_BITS = 24;

	/**
	 * <a href="http://tools.ietf.org/html/rfc5246#section-7.4.2">RFC 5246</a>:
	 * <code>ASN.1Cert certificate_list<0..2^24-1>;</code>
	 */
	private static final int CERTIFICATE_LIST_LENGTH = 24;

	// Members ///////////////////////////////////////////////////////////

	/**
	 * This is a sequence (chain) of certificates. The sender's certificate MUST
	 * come first in the list.
	 */
	private X509Certificate[] certificateChain;

	/** The encoded chain of certificates */
	private List<byte[]> encodedChain;

	/** The total length of the {@link CertificateMessage}. */
	private int messageLength;
	
	/**
	 * The SubjectPublicKeyInfo part of the X.509 certificate. Used in
	 * constrained environments for smaller message size.
	 */
	private byte[] rawPublicKeyBytes = null;

	// Constructor ////////////////////////////////////////////////////

	/**
	 * Adds the whole certificate chain to the message and if requested extracts
	 * the raw public key from the server's certificate.
	 * 
	 * @param certificateChain
	 *            the certificate chain (first certificate must be the
	 *            server's).
	 * @param useRawPublicKey
	 *            whether only the raw public key (SubjectPublicKeyInfo) is
	 *            needed.
	 */
	public CertificateMessage(X509Certificate[] certificateChain, boolean useRawPublicKey) {
		this.certificateChain = certificateChain;
		if (useRawPublicKey) {
			this.rawPublicKeyBytes = certificateChain[0].getPublicKey().getEncoded();
		}
	}

	/**
	 * Called when only the raw public key is available (and not the whole
	 * certificate chain).
	 * 
	 * @param rawPublicKeyBytes
	 *            the raw public key (SubjectPublicKeyInfo).
	 */
	public CertificateMessage(byte[] rawPublicKeyBytes) {
		this.rawPublicKeyBytes = rawPublicKeyBytes;
	}

	// Methods ////////////////////////////////////////////////////////

	@Override
	public HandshakeType getMessageType() {
		return HandshakeType.CERTIFICATE;
	}

	@Override
	public int getMessageLength() {
		if (rawPublicKeyBytes == null) {
			// the certificate chain length uses 3 bytes
			// each certificate's length in the chain also uses 3 bytes
			if (encodedChain == null) {
				messageLength = 3;
				encodedChain = new ArrayList<byte[]>(certificateChain.length);
				for (X509Certificate cert : certificateChain) {
					try {
						byte[] encoded = cert.getEncoded();
						encodedChain.add(encoded);

						// the length of the encoded certificate plus 3 bytes
						// for
						// the length
						messageLength += encoded.length + 3;
					} catch (CertificateEncodingException e) {
						encodedChain = null;
						LOG.severe("Could not encode the certificate.");
						e.printStackTrace();
					}
				}
			}
		} else {
			// fixed: 3 bytes for certificates length field + 3 bytes for
			// certificate length
			messageLength = 6 + rawPublicKeyBytes.length;
			// TODO still unclear whether the payload only consists of the raw public key
		}
		return messageLength;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(super.toString());
		if (rawPublicKeyBytes == null) {
			sb.append("\t\tCertificates Length: " + (getMessageLength() - 3) + "\n");
			int index = 0;
			for (X509Certificate cert : certificateChain) {
				sb.append("\t\t\tCertificate Length: " + encodedChain.get(index).length + "\n");
				sb.append("\t\t\tCertificate: " + cert.toString() + "\n");

				index++;
			}
		} else {
			sb.append("\t\tRaw Public Key:\n");
			sb.append("\t\t\t" + getPublicKey().toString() + "\n");
		}

		return sb.toString();
	}

	public X509Certificate[] getCertificateChain() {
		return certificateChain;
	}
	
	/**
	 * Tries to verify the peer's certificate. Checks its validity and verifies
	 * that it was signed with the stated private key.
	 * 
	 * @throws HandshakeException
	 *             if the certificate could not be verified.
	 */
	public void verifyCertificate() throws HandshakeException {
		if (rawPublicKeyBytes == null) {
			X509Certificate cert = certificateChain[0];
			try {
				cert.checkValidity();
			} catch (Exception e) {
				AlertMessage alert = new AlertMessage(AlertLevel.FATAL, AlertDescription.CERTIFICATE_EXPIRED);
				throw new HandshakeException("Certificate not valid.", alert);
			}

			try {
				cert.verify(getPublicKey());
			} catch (Exception e) {
				AlertMessage alert = new AlertMessage(AlertLevel.FATAL, AlertDescription.CERTIFICATE_REVOKED);
				throw new HandshakeException("Certificate could not be verified.", alert);
			}

		}
	}

	// Serialization //////////////////////////////////////////////////

	@Override
	public byte[] fragmentToByteArray() {
		DatagramWriter writer = new DatagramWriter();

		if (rawPublicKeyBytes == null) {
			// the size of the certificate chain
			writer.write(getMessageLength() - 3, CERTIFICATE_LIST_LENGTH);
			for (byte[] encoded : encodedChain) {
				// the size of the current certificate
				writer.write(encoded.length, CERTIFICATE_LENGTH_BITS);
				// the encoded current certificate
				writer.writeBytes(encoded);
			}
		} else {
			writer.write(getMessageLength() - 3, CERTIFICATE_LIST_LENGTH);
			writer.write(rawPublicKeyBytes.length, CERTIFICATE_LENGTH_BITS);
			writer.writeBytes(rawPublicKeyBytes);
		}

		return writer.toByteArray();
	}

	public static HandshakeMessage fromByteArray(byte[] byteArray, boolean useRawPublicKey) {

		DatagramReader reader = new DatagramReader(byteArray);

		int certificateChainLength = reader.read(CERTIFICATE_LENGTH_BITS);
		CertificateMessage message;
		if (useRawPublicKey) {
			int certificateLength = reader.read(CERTIFICATE_LENGTH_BITS);
			byte[] rawPublicKey = reader.readBytes(certificateLength);
			message = new CertificateMessage(rawPublicKey);
		} else {
			List<Certificate> certs = new ArrayList<Certificate>();

			CertificateFactory certificateFactory = null;
			while (certificateChainLength > 0) {
				int certificateLength = reader.read(CERTIFICATE_LENGTH_BITS);
				byte[] certificate = reader.readBytes(certificateLength);

				// the size of the length and the actual length of the encoded
				// certificate
				certificateChainLength -= 3 + certificateLength;

				try {
					if (certificateFactory == null) {
						certificateFactory = CertificateFactory.getInstance("X.509");
					}
					Certificate cert = certificateFactory.generateCertificate(new ByteArrayInputStream(certificate));
					certs.add(cert);
				} catch (CertificateException e) {
					LOG.severe("Could not generate the certificate.");
					e.printStackTrace();
				}
			}

			message = new CertificateMessage(certs.toArray(new X509Certificate[certs.size()]), useRawPublicKey);
		}
		return message;
	}

	/**
	 * @return the server's public contained in this certificate.
	 */
	public PublicKey getPublicKey() {
		PublicKey publicKey = null;

		if (rawPublicKeyBytes == null) {
			publicKey = certificateChain[0].getPublicKey();
		} else {
			// get server's public key from Raw Public Key
			EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(rawPublicKeyBytes);
			try {
				// TODO make instance variable
				publicKey = KeyFactory.getInstance("EC").generatePublic(publicKeySpec);
			} catch (Exception e) {
				LOG.severe("Could not reconstruct the server's public key.");
				e.printStackTrace();
			}
		}
		return publicKey;

	}

}
