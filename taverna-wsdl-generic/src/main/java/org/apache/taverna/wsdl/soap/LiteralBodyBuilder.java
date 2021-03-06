/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements. See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership. The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.apache.taverna.wsdl.soap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.wsdl.WSDLException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.soap.SOAPElement;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPFactory;

import org.apache.taverna.wsdl.parser.ArrayTypeDescriptor;
import org.apache.taverna.wsdl.parser.BaseTypeDescriptor;
import org.apache.taverna.wsdl.parser.TypeDescriptor;
import org.apache.taverna.wsdl.parser.UnknownOperationException;
import org.apache.taverna.wsdl.parser.WSDLParser;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * An implementation of BodyBuilder that supports creating the SOAP body for
 * Webservices based upon a WSDL with Literal style.
 * 
 * @author Stuart Owen
 * @author Stian Soiland-Reyes
 * 
 */
@SuppressWarnings("unchecked")
public class LiteralBodyBuilder extends AbstractBodyBuilder {

	private static Logger logger = Logger.getLogger(LiteralBodyBuilder.class);

	private static final String TYPE = "type";

	public LiteralBodyBuilder(String style, WSDLParser parser, String operationName, List<TypeDescriptor> inputDescriptors) {
		super(style, parser, operationName,inputDescriptors);
	}

	@Override
	protected Use getUse() {
		return Use.LITERAL;
	}

	@Override
	public SOAPElement build(Map inputMap) throws WSDLException,
			ParserConfigurationException, SOAPException, IOException,
			SAXException, UnknownOperationException {

		SOAPElement body = super.build(inputMap);

		if (getStyle() == Style.DOCUMENT) {
			fixTypeAttributes(body);
		}

		return body;
	}

	@Override
	protected Element createSkeletonElementForSingleItem(
			Map<String, String> namespaceMappings, TypeDescriptor descriptor,
			String inputName, String typeName) {
		if (getStyle()==Style.DOCUMENT) {
                        return createElementNS("", descriptor.getQname().getLocalPart());
			
		} else {
                    return createElementNS("", inputName);
		}
	}
	
        private void fixTypeAttributes(Node parent) {
		if (parent.getNodeType() == Node.ELEMENT_NODE) {
			Element el = (Element) parent;
			if (parent.hasAttributes()) {
				NamedNodeMap attributes = parent.getAttributes();
				List<Node> attributeNodesForRemoval = new ArrayList<Node>();
				for (int i = 0; i < attributes.getLength(); i++) {
					Node node = attributes.item(i);
					
					if (XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(node.getNamespaceURI()) && TYPE.equals(node.getLocalName())) {
						// TAV-712 - don't just strip out xsi:type - let's fix the
						// name prefixes instead
						
						String xsiType = node.getTextContent();
						// Resolve prefix of xsi type
						String[] xsiTypeSplitted = xsiType.split(":", 2);
						String xsiTypePrefix = "";
						String xsiTypeName;
						if (xsiTypeSplitted.length == 1) {
							// No prefix
							xsiTypeName = xsiTypeSplitted[0];
						} else {
							xsiTypePrefix = xsiTypeSplitted[0];
							xsiTypeName = xsiTypeSplitted[1];
						}
						
						String xsiTypeNS;
						if (parent instanceof SOAPElement) {
							xsiTypeNS = ((SOAPElement)parent).getNamespaceURI(xsiTypePrefix);
						} else {
							xsiTypeNS = node
									.lookupNamespaceURI(xsiTypePrefix);
						}
						// Use global namespace prefixes						
						String newPrefix = namespaceMappings.get(xsiTypeNS);
						if (newPrefix == null) {
							logger.warn("Can't find prefix for xsi:type namespace " + xsiTypeNS + " - keeping old " + xsiType);
						} else {
							String newXsiType = newPrefix + ":" + xsiTypeName;
							node.setTextContent(newXsiType);	
							logger.info("Replacing " + xsiType + " with " + newXsiType);
						}
					}
				}
				for (Node node : attributeNodesForRemoval) {
					el.removeAttributeNS(node.getNamespaceURI(), node
							.getLocalName());
				}
			}
		}
		for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
			fixTypeAttributes(parent.getChildNodes().item(i));
		}
	}

	@Override
	protected Element createElementForArrayType(
			Map<String, String> namespaceMappings, String inputName,
			Object dataValue, TypeDescriptor descriptor, String mimeType,
			String typeName) throws ParserConfigurationException, SAXException,
			IOException, UnknownOperationException {

		ArrayTypeDescriptor arrayDescriptor = (ArrayTypeDescriptor) descriptor;
		TypeDescriptor elementType = arrayDescriptor.getElementType();
		int size = 0;

                Element el = createElementNS("", inputName);

		if (dataValue instanceof List) {
			List dataValues = (List) dataValue;
			size = dataValues.size();
			populateElementWithList(mimeType, el, dataValues, elementType);
		} else {
			
			// if mime type is text/xml then the data is an array in xml form,
			// else its just a single primitive element
			if (mimeType.equals("'text/xml'")) {
                                DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
                                builderFactory.setNamespaceAware(true);
                                DocumentBuilder docBuilder = builderFactory.newDocumentBuilder();
				Document doc = docBuilder.parse(new ByteArrayInputStream(
						dataValue.toString().getBytes()));
				Node child = doc.getDocumentElement().getFirstChild();

				while (child != null) {
					size++;
					el.appendChild(el.getOwnerDocument()
							.importNode(child, true));
					child = child.getNextSibling();
				}
			} else {
				String tag = "item";
				if (elementType instanceof BaseTypeDescriptor) {
					tag = elementType.getType();
				} else {
					tag = elementType.getName();
				}
				Element item = el.getOwnerDocument().createElement(tag);
				populateElementWithObjectData(mimeType, item, dataValue, descriptor);
				el.appendChild(item);
			}

		}

		return el;
	}

	@Override
	protected SOAPElement addElementToBody(String operationNamespace, SOAPElement body, Element el) throws SOAPException {
                SOAPElement element = SOAPFactory.newInstance().createElement(el);
                
		if (getStyle()==Style.DOCUMENT) {
                    // el itself is a body
                        Node node = el.getOwnerDocument().renameNode(el, operationNamespace, el.getLocalName());
                        node.setPrefix(body.getPrefix()); // unnecessary, but to keep junits happy
                        
                        body = SOAPFactory.newInstance().createElement((Element)node);

		} else {
			body.addChildElement(element);
		}
		return body;
	}
	
	

}
