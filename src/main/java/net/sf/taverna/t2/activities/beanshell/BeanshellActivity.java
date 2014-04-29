/*******************************************************************************
 * Copyright (C) 2007 The University of Manchester
 *
 *  Modifications to the initial code base are copyright of their
 *  respective authors, or their employers as appropriate.
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2.1 of
 *  the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 ******************************************************************************/
package net.sf.taverna.t2.activities.beanshell;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import net.sf.taverna.t2.activities.dependencyactivity.AbstractAsynchronousDependencyActivity;
import net.sf.taverna.t2.reference.ErrorDocumentService;
import net.sf.taverna.t2.reference.ReferenceService;
import net.sf.taverna.t2.reference.ReferenceServiceException;
import net.sf.taverna.t2.reference.T2Reference;
import net.sf.taverna.t2.workflowmodel.OutputPort;
import net.sf.taverna.t2.workflowmodel.processor.activity.ActivityInputPort;
import net.sf.taverna.t2.workflowmodel.processor.activity.AsynchronousActivityCallback;

import org.apache.log4j.Logger;

import uk.org.taverna.configuration.app.ApplicationConfiguration;
import bsh.EvalError;
import bsh.Interpreter;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An Activity providing Beanshell functionality.
 *
 * @author David Withers
 * @author Stuart Owen
 * @author Alex Nenadic
 */
public class BeanshellActivity extends AbstractAsynchronousDependencyActivity {

	public static final String URI = "http://ns.taverna.org.uk/2010/activity/beanshell";

	protected BeanshellActivityConfigurationBean configurationBean;

	private static Logger logger = Logger.getLogger(BeanshellActivity.class);

	private Interpreter interpreter;

	private static String CLEAR_COMMAND = "clear();";

	private JsonNode json;

	public BeanshellActivity(ApplicationConfiguration applicationConfiguration) {
		super(applicationConfiguration);
		createInterpreter();
	}

	@Override
	public JsonNode getConfiguration() {
		return json;
	}

	@Override
	public void configure(JsonNode json) {
		this.json = json;
		checkGranularDepths();
	}

	/**
	 * Creates the interpreter required to run the beanshell script, and assigns the correct
	 * classloader
	 * setting according to the
	 */
	private void createInterpreter() {
		interpreter = new Interpreter();
	}

	/**
	 * As the Beanshell activity currently only can output values at the
	 * specified depth, the granular depths should always be equal to the actual
	 * depth.
	 * <p>
	 * Workflow definitions created with Taverna 2.0b1 would not honour this and always set the
	 * granular depth to 0.
	 * <p>
	 * This method modifies the granular depths to be equal to the depths.
	 */
	protected void checkGranularDepths() {
		for (OutputPort outputPort : getOutputPorts()) {
			if (outputPort.getGranularDepth() != outputPort.getDepth()) {
				logger.warn("Replacing granular depth of port " + outputPort.getName());
//				outputPort.setGranularDepth(outputPort.getDepth());
			}
		}
	}

	public ActivityInputPort getInputPort(String name) {
		for (ActivityInputPort port : getInputPorts()) {
			if (port.getName().equals(name)) {
				return port;
			}
		}
		return null;
	}

	private void clearInterpreter() {
		try {
			interpreter.eval(CLEAR_COMMAND);
		} catch (EvalError e) {
			logger.error("Could not clear the interpreter", e);
		}
	}

	@Override
	public void executeAsynch(final Map<String, T2Reference> data,
			final AsynchronousActivityCallback callback) {
		callback.requestRun(new Runnable() {

			public void run() {

				// Workflow run identifier (needed when classloader sharing is set to 'workflow').
				String procID = callback.getParentProcessIdentifier();
				String workflowRunID;
				if (procID.contains(":")) {
					workflowRunID = procID.substring(0, procID.indexOf(':'));
				} else {
					workflowRunID = procID; // for tests, will be an empty string
				}

				synchronized (interpreter) {

				// Configure the classloader for executing the Beanshell
				if (classLoader == null) {
					try {
						classLoader = findClassLoader(json, workflowRunID);
						interpreter.setClassLoader(classLoader);
					} catch (RuntimeException rex) {
						String message = "Unable to obtain the classloader for Beanshell service";
						callback.fail(message, rex);
						return;
					}

				}


					ReferenceService referenceService = callback.getContext().getReferenceService();

					Map<String, T2Reference> outputData = new HashMap<String, T2Reference>();

					clearInterpreter();
					try {
						// set inputs
						for (String inputName : data.keySet()) {
							ActivityInputPort inputPort = getInputPort(inputName);
							Object input = referenceService.renderIdentifier(data.get(inputName),
									inputPort.getTranslatedElementClass(), callback.getContext());
							inputName = sanatisePortName(inputName);
							interpreter.set(inputName, input);
						}
						// run
						interpreter.eval(json.get("script").asText());
						// get outputs
						for (OutputPort outputPort : getOutputPorts()) {
							String name = outputPort.getName();
							Object value = interpreter.get(name);
							if (value == null) {
								ErrorDocumentService errorDocService = referenceService
										.getErrorDocumentService();
								value = errorDocService.registerError(
										"No value produced for output variable " + name,
										outputPort.getDepth(), callback.getContext());
							}
							outputData.put(name, referenceService.register(value,
									outputPort.getDepth(), true, callback.getContext()));
						}
						callback.receiveResult(outputData, new int[0]);
					} catch (EvalError e) {
						callback.fail("Error evaluating the beanshell script " + this, e);
					} catch (ReferenceServiceException e) {
						callback.fail("Error accessing beanshell input/output data for " + this, e);
					}
					clearInterpreter();
				}
			}

			/**
			 * Removes any invalid characters from the port name.
			 * For example, xml-text would become xmltext.
			 *
			 * @param name
			 * @return
			 */
			private String sanatisePortName(String name) {
				String result = name;
				if (Pattern.matches("\\w++", name) == false) {
					result = "";
					for (char c : name.toCharArray()) {
						if (Character.isLetterOrDigit(c) || c == '_') {
							result += c;
						}
					}
				}
				return result;
			}
		});

	}
}
