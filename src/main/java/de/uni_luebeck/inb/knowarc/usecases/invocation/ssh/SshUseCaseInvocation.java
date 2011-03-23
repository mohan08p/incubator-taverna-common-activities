/* Part of the KnowARC Janitor Use-case processor for taverna
 *  written 2007-2010 by Hajo Nils Krabbenhoeft and Steffen Moeller
 *  University of Luebeck, Institute for Neuro- and Bioinformatics
 *  University of Luebeck, Institute for Dermatolgy
 *
 *  This package is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License as published by
 *  the Free Software Foundation; either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This package is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this package; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301 USA
 */

package de.uni_luebeck.inb.knowarc.usecases.invocation.ssh;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Vector;

import net.sf.taverna.t2.reference.ReferenceService;
import net.sf.taverna.t2.reference.ReferencedDataNature;
import net.sf.taverna.t2.reference.T2Reference;
import net.sf.taverna.t2.reference.impl.external.file.FileReference;

import org.apache.commons.io.IOUtils;
import org.globus.ftp.exception.NotImplementedException;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.ChannelSftp.LsEntry;

import de.uni_luebeck.inb.knowarc.usecases.ScriptInput;
import de.uni_luebeck.inb.knowarc.usecases.ScriptOutput;
import de.uni_luebeck.inb.knowarc.usecases.UseCaseDescription;
import de.uni_luebeck.inb.knowarc.usecases.invocation.AskUserForPw;
import de.uni_luebeck.inb.knowarc.usecases.invocation.UseCaseInvocation;

/**
 * The job is executed by connecting to a worker pc using ssh, i.e. not via the
 * grid.
 * 
 * @author Hajo Krabbenhoeft
 */
public class SshUseCaseInvocation extends UseCaseInvocation {
	
	public static final String SSH_USE_CASE_INVOCATION_TYPE = "D0A4CDEB-DD10-4A8E-A49C-8871003083D8";
	private String tmpname;
	private final SshNode workerNode;
	private final AskUserForPw askUserForPw;
	
	private ChannelExec running;
	private final ByteArrayOutputStream stdout_buf = new ByteArrayOutputStream();
	private final ByteArrayOutputStream stderr_buf = new ByteArrayOutputStream();

    private static HashMap<String, Object> nodeLock = new HashMap<String,Object>();

	public static String test(final SshNode workerNode, final AskUserForPw askUserForPw) {
		try {
			Session sshSession = SshPool.getSshSession(workerNode, askUserForPw);

			ChannelSftp sftpTest = (ChannelSftp) sshSession.openChannel("sftp");
			sftpTest.connect();
			sftpTest.cd(workerNode.getDirectory());
			sftpTest.disconnect();
			sshSession.disconnect();
		} catch (JSchException e) {
			return e.toString();
		} catch (SftpException e) {
			return e.toString();
		}
		return null;
	}
	
	public SshUseCaseInvocation(UseCaseDescription desc, SshNode workerNodeA, AskUserForPw askUserForPwA)
			throws JSchException, SftpException {
		this.workerNode = workerNodeA;
		this.askUserForPw = askUserForPwA;
		usecase = desc;

		ChannelSftp sftp = SshPool.getSftpPutChannel(workerNode, askUserForPw);
		synchronized(getNodeLock(workerNode)) {

			System.err.println("Changing remote directory to " + workerNode.getDirectory());
			sftp.cd(workerNode.getDirectory());
			Random rnd = new Random();
			while (true) {
				tmpname = "usecase" + rnd.nextLong();
				try {
					sftp.lstat(workerNode.getDirectory() + tmpname);
					continue;
				} catch (Exception e) {
					// file seems to not exist :)
				}
				sftp.mkdir(workerNode.getDirectory() + tmpname);
				sftp.cd(workerNode.getDirectory() + tmpname);
				break;
			}
		}
	}

	@Override
	public void putFile(String name, byte[] contents) {
	    ChannelSftp sftp;
	    try {
		sftp = SshPool.getSftpPutChannel(workerNode, askUserForPw);
		synchronized(getNodeLock(workerNode)) {
		    try {
			sftp.cd(workerNode.getDirectory() + tmpname);
		    } catch (SftpException e1) {
			System.err.println("Unable to change directory" + e1);
		    }
		    try {
			sftp.put(new ByteArrayInputStream(contents), name);
		    } catch (Exception e) {
			System.err.println("Error in putFile" + e);
		    }
		    //				sftp.disconnect();
		}
	    } catch (JSchException e2) {
		// TODO Auto-generated catch block
		e2.printStackTrace();
	    }
	}

	@Override
	public void putReference(String name, String source) throws NotImplementedException {
		throw new NotImplementedException();
	}

	private void recursiveDelete(String path) throws SftpException, JSchException {
		if (!path.startsWith(workerNode.getDirectory() + tmpname))
			return;
		ChannelSftp sftp = SshPool.getSftpPutChannel(workerNode, askUserForPw);
		synchronized(getNodeLock(workerNode)) {
		    Vector<?> entries = sftp.ls(path);
		    for (Object object : entries) {
			LsEntry entry = (LsEntry) object;
			if (entry.getFilename().equals(".") || entry.getFilename().equals(".."))
				continue;
			if (entry.getAttrs().isDir())
				recursiveDelete(path + entry.getFilename() + "/");
			else
				sftp.rm(path + entry.getFilename());
		}
		sftp.rmdir(path);
		}
	}

	@Override
	public void Cleanup() {
		try {
//			sftp.cd(workerNode.getDirectory());
//			recursiveDelete(workerNode.getDirectory() + tmpname + "/");
		} catch (Exception e) {
		    // TODO
		}
		try {
			if (running != null) {
			    running.disconnect();
			}
//			sftp.disconnect();
//			sshSession.disconnect();
		} catch (Exception e) {
		}
	}

	/**
	 * Transforms an input stream towards an entity that Taverna can work with.
	 * 
	 * @param read
	 * @param binary
	 * @return
	 * @throws IOException
	 */
	private Object file2data(InputStream read, boolean binary) throws IOException {
		byte[] data = new byte[0];
		byte[] buf = new byte[1024];
		int r;
		while (-1 != (r = read.read(buf))) {
			byte[] d2 = new byte[data.length + r];
			System.arraycopy(data, 0, d2, 0, data.length);
			System.arraycopy(buf, 0, d2, data.length, r);
			data = d2;
		}
		if (binary)
			return data;
		else
			return Charset.forName("US-ASCII").decode(ByteBuffer.wrap(data)).toString();
	}

	@Override
	protected void submit_generate_job_inner() throws Exception {
		tags.put("uniqueID", "" + getSubmissionID());
		String command = usecase.getCommand();
		for (String cur : tags.keySet()) {
			command = command.replaceAll("\\Q%%" + cur + "%%\\E", tags.get(cur));
		}
		command = "cd " + workerNode.getDirectory() + tmpname + " && " + command;
		
		running = SshPool.openExecChannel(workerNode, askUserForPw);
		running.setCommand(command);
		running.setOutputStream(stdout_buf);
		running.setErrStream(stderr_buf);
		running.connect();
	}

	@Override
	public HashMap<String, Object> submit_wait_fetch_results() throws Exception {
		while (!running.isClosed()) {
			try {
				Thread.sleep(1000);
			} catch (Exception e) {
			}
		}

		int exitcode = running.getExitStatus();
		if (exitcode != 0)
			throw new Exception("nonzero exit code");

		HashMap<String, Object> results = new HashMap<String, Object>();
		results.put("STDOUT", Charset.forName("US-ASCII").decode(ByteBuffer.wrap(stdout_buf.toByteArray())).toString());
		results.put("STDERR", Charset.forName("US-ASCII").decode(ByteBuffer.wrap(stderr_buf.toByteArray())).toString());
		for (Map.Entry<String, ScriptOutput> cur : usecase.getOutputs().entrySet()) {
		    SshUrl url = new SshUrl(workerNode);
		    url.setSubDirectory(tmpname);
		    url.setFileName(cur.getValue().getPath());
		    results.put(cur.getKey(), url);
		}

		//		sftp.disconnect();
       		running.disconnect();
		return results;
	}

	@Override
	public String setOneInput(ReferenceService referenceService,
			T2Reference t2Reference, ScriptInput input)
			throws UnsupportedEncodingException, IOException {
		String target = null;
		String remoteName = null;
		if (input.isFile()) {
			remoteName = input.getTag();
		} else if (input.isTempFile()) {
			remoteName = "tempfile." + (nTempFiles++) + ".tmp";

		}
		if (input.isFile() || input.isTempFile()) {
		target = workerNode.getDirectory() + tmpname + "/" + remoteName;
		System.err.println("Target is " + target);
				try {
				    ChannelSftp sftp = SshPool.getSftpPutChannel(workerNode, askUserForPw);
				    synchronized (getNodeLock(workerNode)) {
					    InputStream r = getAsStream(referenceService, t2Reference);
					    sftp.put(r, target);
					    r.close();
					}
				} catch (SftpException e) {
					throw new IOException(e);
				} catch (JSchException e) {
					throw new IOException(e);
					}
			return target;
		} else {
			String value = (String) referenceService.renderIdentifier(t2Reference, String.class, dummyContext);
			return value;
			
		}
	}

    private static Object getNodeLock(final SshNode node) {
	return getNodeLock(node.getHost());
    }

    private static synchronized Object getNodeLock(String hostName) {
	if (!nodeLock.containsKey(hostName)) {
	    nodeLock.put(hostName, new Object());
	}
	return nodeLock.get(hostName);
    }
}