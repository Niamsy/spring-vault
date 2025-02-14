/*
 * Copyright 2016-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.vault.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.KeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keystore utility to create a {@link KeyStore} containing a {@link CertificateBundle}
 * with the certificate chain and its private key.
 *
 * @author Mark Paluch
 */
class KeystoreUtil {

	private static final CertificateFactory CERTIFICATE_FACTORY;

	private static final KeyFactory KEY_FACTORY;

	static {

		try {
			CERTIFICATE_FACTORY = CertificateFactory.getInstance("X.509");
		}
		catch (CertificateException e) {
			throw new IllegalStateException("No X.509 Certificate available", e);
		}

		try {
			KEY_FACTORY = KeyFactory.getInstance("RSA");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("No RSA KeyFactory available", e);
		}
	}

	/**
	 * Create a {@link KeyStore} containing the {@link KeySpec} and {@link X509Certificate
	 * certificates} using the given {@code keyAlias}.
	 * @param keyAlias
	 * @param certificates
	 * @return
	 * @throws GeneralSecurityException
	 * @throws IOException
	 */
	static KeyStore createKeyStore(String keyAlias, KeySpec privateKeySpec, X509Certificate... certificates)
			throws GeneralSecurityException, IOException {

		PrivateKey privateKey = KEY_FACTORY.generatePrivate(privateKeySpec);

		KeyStore keyStore = createKeyStore();

		List<X509Certificate> certChain = new ArrayList<>();
		Collections.addAll(certChain, certificates);

		keyStore.setKeyEntry(keyAlias, privateKey, new char[0],
				certChain.toArray(new java.security.cert.Certificate[certChain.size()]));

		return keyStore;
	}

	/**
	 * Create a {@link KeyStore} containing the {@link X509Certificate certificates}
	 * stored with as {@code cert_0, cert_1...cert_N}.
	 * @param certificates
	 * @return
	 * @throws GeneralSecurityException
	 * @throws IOException
	 * @since 2.0
	 */
	static KeyStore createKeyStore(X509Certificate... certificates) throws GeneralSecurityException, IOException {

		KeyStore keyStore = createKeyStore();

		int counter = 0;
		for (X509Certificate certificate : certificates) {
			keyStore.setCertificateEntry(String.format("cert_%d", counter++), certificate);
		}

		return keyStore;
	}

	static X509Certificate getCertificate(byte[] source) throws CertificateException {

		List<X509Certificate> certificates = getCertificates(CERTIFICATE_FACTORY, source);

		return certificates.stream().findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No X509Certificate found"));
	}

	/**
	 * Create an empty {@link KeyStore}.
	 * @return the {@link KeyStore}.
	 * @throws GeneralSecurityException
	 * @throws IOException
	 */
	private static KeyStore createKeyStore() throws GeneralSecurityException, IOException {

		KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
		keyStore.load(null, new char[0]);

		return keyStore;
	}

	private static List<X509Certificate> getCertificates(CertificateFactory cf, byte[] source)
			throws CertificateException {

		List<X509Certificate> x509Certificates = new ArrayList<>();

		ByteArrayInputStream bis = new ByteArrayInputStream(source);
		while (bis.available() > 0) {
			java.security.cert.Certificate cert = cf.generateCertificate(bis);

			if (cert instanceof X509Certificate) {
				x509Certificates.add((X509Certificate) cert);
			}
		}

		return x509Certificates;
	}

	/**
	 * Convert PKCS#1 encoded public key into RSAPublicKeySpec.
	 * <p/>
	 * <p/>
	 * The ASN.1 syntax for the public key with CRT is
	 * <p/>
	 *
	 * <pre>
	 * --
	 * -- Representation of RSA public key with information for the CRT algorithm.
	 * --
	 * RSAPublicKey ::= SEQUENCE {
	 *   modulus           INTEGER,  -- n
	 *   publicExponent    INTEGER,  -- e
	 * }
	 * </pre>
	 *
	 * Supports PEM objects with a {@code SEQUENCE} and {@code OBJECT IDENTIFIER} header
	 * where the actual key sequence is represented as {@code BIT_STRING} (as of
	 * {@code openssl -pubout} format).
	 * @param keyBytes PKCS#1 encoded key
	 * @return KeySpec
	 * @since 2.3
	 */
	static RSAPublicKeySpec getRSAPublicKeySpec(byte[] keyBytes) throws IOException, IllegalStateException {
		DerParser parser = new DerParser(keyBytes);

		Asn1Object sequence = parser.read();
		if (sequence.getType() != DerParser.SEQUENCE) {
			throw new IllegalStateException("Invalid DER: not a sequence");
		}

		// Parse inside the sequence
		parser = sequence.getParser();
		Asn1Object object = parser.read();

		if (object.type == DerParser.SEQUENCE) {

			Asn1Object read = object.getParser().read();
			if (!ObjectIdentifiers.RSA.equalsIgnoreCase(read.getString())) {
				throw new IllegalStateException("Unsupported Public Key Algorithm. Expected RSA ("
						+ ObjectIdentifiers.RSA + "), but was: " + read.getString());
			}

			Asn1Object bitString = parser.read();
			if (bitString.getType() != DerParser.BIT_STRING) {
				throw new IllegalStateException("Invalid DER: not a bit string");
			}

			parser = new DerParser(bitString.getValue());
			sequence = parser.read();

			if (sequence.getType() != DerParser.SEQUENCE) {
				throw new IllegalStateException("Invalid DER: not a sequence");
			}

			parser = sequence.getParser();
		}

		BigInteger modulus = parser.read().getInteger();
		BigInteger publicExp = parser.read().getInteger();

		return new RSAPublicKeySpec(modulus, publicExp);
	}

	/**
	 * Convert PKCS#1 encoded private key into RSAPrivateCrtKeySpec.
	 * <p/>
	 * <p/>
	 * The ASN.1 syntax for the private key with CRT is
	 * <p/>
	 *
	 * <pre>
	 * --
	 * -- Representation of RSA private key with information for the CRT algorithm.
	 * --
	 * RSAPrivateKey ::= SEQUENCE {
	 *   version           Version,
	 *   modulus           INTEGER,  -- n
	 *   publicExponent    INTEGER,  -- e
	 *   privateExponent   INTEGER,  -- d
	 *   prime1            INTEGER,  -- p
	 *   prime2            INTEGER,  -- q
	 *   exponent1         INTEGER,  -- d mod (p-1)
	 *   exponent2         INTEGER,  -- d mod (q-1)
	 *   coefficient       INTEGER,  -- (inverse of q) mod p
	 *   otherPrimeInfos   OtherPrimeInfos OPTIONAL
	 * }
	 * </pre>
	 * @param keyBytes PKCS#1 encoded key
	 * @return KeySpec
	 */
	static RSAPrivateCrtKeySpec getRSAPrivateKeySpec(byte[] keyBytes) throws IOException {
		DerParser parser = new DerParser(keyBytes);

		Asn1Object sequence = parser.read();
		if (sequence.getType() != DerParser.SEQUENCE) {
			throw new IllegalStateException("Invalid DER: not a sequence");
		}

		// Parse inside the sequence
		parser = sequence.getParser();

		parser.read(); // Skip version
		BigInteger modulus = parser.read().getInteger();
		BigInteger publicExp = parser.read().getInteger();
		BigInteger privateExp = parser.read().getInteger();
		BigInteger prime1 = parser.read().getInteger();
		BigInteger prime2 = parser.read().getInteger();
		BigInteger exp1 = parser.read().getInteger();
		BigInteger exp2 = parser.read().getInteger();
		BigInteger crtCoef = parser.read().getInteger();

		return new RSAPrivateCrtKeySpec(modulus, publicExp, privateExp, prime1, prime2, exp1, exp2, crtCoef);
	}

	private static class ObjectIdentifiers {

		static final String RSA = "1.2.840.113549.1.1.1";

	}

	/**
	 * A bare-minimum ASN.1 DER decoder, just having enough functions to decode PKCS#1
	 * private keys. Especially, it doesn't handle explicitly tagged types with an outer
	 * tag.
	 * <p/>
	 * <p/>
	 * This parser can only handle one layer. To parse nested constructs, get a new parser
	 * for each layer using <code>Asn1Object.getParser()</code>.
	 * <p/>
	 * <p/>
	 * There are many DER decoders in JRE but using them will tie this program to a
	 * specific JCE/JVM.
	 */
	@SuppressWarnings("unused")
	private static class DerParser {

		// Classes
		static final int UNIVERSAL = 0x00;
		static final int APPLICATION = 0x40;
		static final int CONTEXT = 0x80;
		static final int PRIVATE = 0xC0;

		// Constructed Flag
		static final int CONSTRUCTED = 0x20;

		// Tag and data types
		static final int ANY = 0x00;
		static final int BOOLEAN = 0x01;
		static final int INTEGER = 0x02;
		static final int BIT_STRING = 0x03;
		static final int OCTET_STRING = 0x04;
		static final int NULL = 0x05;
		static final int OID = 0x06;
		static final int REAL = 0x09;
		static final int ENUMERATED = 0x0a;

		static final int SEQUENCE = 0x10;
		static final int SET = 0x11;

		static final int NUMERIC_STRING = 0x12;
		static final int PRINTABLE_STRING = 0x13;
		static final int VIDEOTEX_STRING = 0x15;
		static final int IA5_STRING = 0x16;
		static final int GRAPHIC_STRING = 0x19;
		static final int ISO646_STRING = 0x1A;
		static final int GENERAL_STRING = 0x1B;

		static final int UTF8_STRING = 0x0C;
		static final int UNIVERSAL_STRING = 0x1C;
		static final int BMP_STRING = 0x1E;

		static final int UTC_TIME = 0x17;

		protected InputStream in;

		/**
		 * Create a new DER decoder from an input stream.
		 * @param in The DER encoded stream
		 */
		DerParser(InputStream in) {
			this.in = in;
		}

		/**
		 * Create a new DER decoder from a byte array.
		 * @param bytes The encoded bytes
		 */
		DerParser(byte[] bytes) {
			this(new ByteArrayInputStream(bytes));
		}

		/**
		 * Read next object. If it's constructed, the value holds encoded content and it
		 * should be parsed by a new parser from <code>Asn1Object.getParser</code>.
		 * @return A object
		 */
		public Asn1Object read() throws IOException {

			int tag = this.in.read();

			if (tag == -1) {
				throw new IllegalStateException("Invalid DER: stream too short, missing tag");
			}

			int length = getLength();

			if (tag == BIT_STRING) {
				// Not sure what to do with this one.
				int padBits = this.in.read();
				length--;
			}

			byte[] value = new byte[length];
			int n = this.in.read(value);
			if (n < length) {
				throw new IllegalStateException("Invalid DER: stream too short, missing value");
			}

			return new Asn1Object(tag, length, value);
		}

		/**
		 * Decode the length of the field. Can only support length encoding up to 4
		 * octets.
		 * <p/>
		 * <p/>
		 * In BER/DER encoding, length can be encoded in 2 forms,
		 * <ul>
		 * <li>Short form. One octet. Bit 8 has value "0" and bits 7-1 give the length.
		 * <li>Long form. Two to 127 octets (only 4 is supported here). Bit 8 of first
		 * octet has value "1" and bits 7-1 give the number of additional length octets.
		 * Second and following octets give the length, base 256, most significant digit
		 * first.
		 * </ul>
		 * @return The length as integer
		 */
		private int getLength() throws IOException {

			int i = this.in.read();
			if (i == -1) {
				throw new IllegalStateException("Invalid DER: length missing");
			}

			// A single byte short length
			if ((i & ~0x7F) == 0) {
				return i;
			}

			int num = i & 0x7F;

			// We can't handle length longer than 4 bytes
			if (i >= 0xFF || num > 4) {
				throw new IllegalStateException("Invalid DER: length field too big (" + i + ")");
			}

			byte[] bytes = new byte[num];
			int n = this.in.read(bytes);
			if (n < num) {
				throw new IllegalStateException("Invalid DER: length too short");
			}

			return new BigInteger(1, bytes).intValue();
		}

	}

	/**
	 * An ASN.1 TLV. The object is not parsed. It can only handle integers and strings.
	 */
	static class Asn1Object {

		private static final long LONG_LIMIT = (Long.MAX_VALUE >> 7) - 0x7f;

		private final int type;

		private final int length;

		private final byte[] value;

		private final int tag;

		/**
		 * Construct a ASN.1 TLV. The TLV could be either a constructed or primitive
		 * entity.
		 * <p/>
		 * <p/>
		 * The first byte in DER encoding is made of following fields,
		 *
		 * <pre>
		 * -------------------------------------------------
		 * |Bit 8|Bit 7|Bit 6|Bit 5|Bit 4|Bit 3|Bit 2|Bit 1|
		 * -------------------------------------------------
		 * |  Class    | CF  |     +      Type             |
		 * -------------------------------------------------
		 * </pre>
		 *
		 * <ul>
		 * <li>Class: Universal, Application, Context or Private
		 * <li>CF: Constructed flag. If 1, the field is constructed.
		 * <li>Type: This is actually called tag in ASN.1. It indicates data type
		 * (Integer, String) or a construct (sequence, choice, set).
		 * </ul>
		 * @param tag Tag or Identifier
		 * @param length Length of the field
		 * @param value Encoded octet string for the field.
		 */
		Asn1Object(int tag, int length, byte[] value) {
			this.tag = tag;
			this.type = tag & 0x1F;
			this.length = length;
			this.value = value;
		}

		int getType() {
			return this.type;
		}

		int getLength() {
			return this.length;
		}

		byte[] getValue() {
			return this.value;
		}

		boolean isConstructed() {
			return (this.tag & DerParser.CONSTRUCTED) == DerParser.CONSTRUCTED;
		}

		/**
		 * For constructed field, return a parser for its content.
		 * @return A parser for the construct.
		 */
		DerParser getParser() throws IOException {

			if (!isConstructed()) {
				throw new IllegalStateException("Invalid DER: can't parse primitive entity");
			}

			return new DerParser(this.value);
		}

		/**
		 * Get the value as integer
		 * @return BigInteger
		 */
		BigInteger getInteger() {

			if (this.type != DerParser.INTEGER) {
				throw new IllegalStateException(String.format("Invalid DER: object (%d) is not integer.", this.type));
			}

			return new BigInteger(this.value);
		}

		/**
		 * Get value as string. Most strings are treated as ISO-8859-1.
		 * @return Java string
		 */
		String getString() throws IOException {

			String encoding;

			switch (this.type) {

			// Not all are ISO-8859-1 but it's the closest thing
			case DerParser.NUMERIC_STRING:
			case DerParser.PRINTABLE_STRING:
			case DerParser.VIDEOTEX_STRING:
			case DerParser.IA5_STRING:
			case DerParser.GRAPHIC_STRING:
			case DerParser.ISO646_STRING:
			case DerParser.GENERAL_STRING:
				encoding = "ISO-8859-1";
				break;

			case DerParser.BMP_STRING:
				encoding = "UTF-16BE";
				break;

			case DerParser.UTF8_STRING:
				encoding = "UTF-8";
				break;

			case DerParser.UNIVERSAL_STRING:
				throw new IOException("Invalid DER: can't handle UCS-4 string");

			case DerParser.OID:
				return getObjectIdentifier(this.value);
			default:
				throw new IOException(String.format("Invalid DER: object (%d) is not a string", this.type));
			}

			return new String(this.value, encoding);
		}

		private static String getObjectIdentifier(byte bytes[]) {
			StringBuffer objId = new StringBuffer();
			long value = 0;
			BigInteger bigValue = null;
			boolean first = true;

			for (int i = 0; i != bytes.length; i++) {
				int b = bytes[i] & 0xff;

				if (value <= LONG_LIMIT) {
					value += (b & 0x7f);
					if ((b & 0x80) == 0) // end of number reached
					{
						if (first) {
							if (value < 40) {
								objId.append('0');
							}
							else if (value < 80) {
								objId.append('1');
								value -= 40;
							}
							else {
								objId.append('2');
								value -= 80;
							}
							first = false;
						}

						objId.append('.');
						objId.append(value);
						value = 0;
					}
					else {
						value <<= 7;
					}
				}
				else {
					if (bigValue == null) {
						bigValue = BigInteger.valueOf(value);
					}
					bigValue = bigValue.or(BigInteger.valueOf(b & 0x7f));
					if ((b & 0x80) == 0) {
						if (first) {
							objId.append('2');
							bigValue = bigValue.subtract(BigInteger.valueOf(80));
							first = false;
						}

						objId.append('.');
						objId.append(bigValue);
						bigValue = null;
						value = 0;
					}
					else {
						bigValue = bigValue.shiftLeft(7);
					}
				}
			}

			return objId.toString();
		}

	}

}
