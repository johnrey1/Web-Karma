/*******************************************************************************
 * Copyright 2012 University of Southern California
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 	http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * This code was developed by the Information Integration Group as part 
 * of the Karma project at the Information Sciences Institute of the 
 * University of Southern California.  For more information, publications, 
 * and related projects, please see: http://www.isi.edu/integration
 ******************************************************************************/
package edu.isi.karma.controller.command.publish;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.isi.karma.controller.command.Command;
import edu.isi.karma.controller.command.CommandException;
import edu.isi.karma.controller.update.AbstractUpdate;
import edu.isi.karma.controller.update.ErrorUpdate;
import edu.isi.karma.controller.update.UpdateContainer;
import edu.isi.karma.geospatial.WorksheetGeospatialContent;
import edu.isi.karma.rep.Worksheet;
import edu.isi.karma.view.VWorkspace;

public class PublishKMLLayerCommand extends Command {
	private final String vWorksheetId;
	private String publicKMLAddress;
	private String kMLTransferServiceURL;

	public enum JsonKeys {
		updateType, fileName
	}

	private static Logger logger = LoggerFactory
			.getLogger(PublishKMLLayerCommand.class);

	protected PublishKMLLayerCommand(String id, String vWorksheetId,
			String ipAddress, String kMLTransferServiceURL) {
		super(id);
		this.vWorksheetId = vWorksheetId;
		this.publicKMLAddress = ipAddress;
		this.kMLTransferServiceURL = kMLTransferServiceURL;
	}

	@Override
	public String getCommandName() {
		return this.getClass().getSimpleName();
	}

	@Override
	public String getTitle() {
		return "Publish KML Layer";
	}

	@Override
	public String getDescription() {
		return null;
	}

	@Override
	public CommandType getCommandType() {
		return CommandType.notInHistory;
	}

	@Override
	public UpdateContainer doIt(VWorkspace vWorkspace) throws CommandException {
		Worksheet worksheet = vWorkspace.getViewFactory()
				.getVWorksheet(vWorksheetId).getWorksheet();

		WorksheetGeospatialContent geo = new WorksheetGeospatialContent(
				worksheet, vWorkspace.getWorkspace().getTagsContainer());
		// Send an error update if no geospatial data found!
		if (geo.hasNoGeospatialData()) {
			return new UpdateContainer(new ErrorUpdate(
					"No geospatial data found in the worksheet!"));
		}

		try {
			final File file = geo.publishKML();
			// Transfer the file to a public server
			boolean transfer = transferFileToPublicServer(file);
			if (!transfer) {
				return new UpdateContainer(
						new ErrorUpdate(
								"Published KML file could not be moved to a public server to display on Google Maps!"));
			}

			return new UpdateContainer(new AbstractUpdate() {
				@Override
				public void generateJson(String prefix, PrintWriter pw,
						VWorkspace vWorkspace) {
					JSONObject outputObject = new JSONObject();
					try {
						outputObject.put(JsonKeys.updateType.name(),
								"PublishKMLUpdate");
						outputObject.put(JsonKeys.fileName.name(),
								publicKMLAddress + file.getName());
						pw.println(outputObject.toString(4));
					} catch (JSONException e) {
						logger.error("Error occured while generating JSON!");
					}
				}
			});
		} catch (FileNotFoundException e) {
			logger.error("KML File not found!", e);
			return new UpdateContainer(
					new ErrorUpdate(
							"Error occurred while publishing KML layer!"));
		}
	}

	@SuppressWarnings("deprecation")
	private boolean transferFileToPublicServer(File file) {
		try {
			logger.info("Starting transfer of the published KML file to a public server to view it on Google Maps ...");
			HttpURLConnection conn = null;
			DataOutputStream dos = null;
			DataInputStream inStream = null;

			String lineEnd = "\r\n";
			String twoHyphens = "--";
			String boundary = "*****";

			int bytesRead, bytesAvailable, bufferSize;
			byte[] buffer;
			int maxBufferSize = 1 * 1024 * 1024;

			// Request the client
			FileInputStream fileInputStream = new FileInputStream(file);

			URL url = new URL(kMLTransferServiceURL);
			conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(5000);
			conn.setDoInput(true);
			conn.setDoOutput(true);
			conn.setUseCaches(false);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Connection", "Keep-Alive");
			conn.setRequestProperty("Content-Type",
					"multipart/form-data;boundary=" + boundary);

			dos = new DataOutputStream(conn.getOutputStream());
			dos.writeBytes(twoHyphens + boundary + lineEnd);
			dos.writeBytes("Content-Disposition: form-data; name=\"upload\";"
					+ " filename=\"" + file.getName() + "\"" + lineEnd);
			dos.writeBytes(lineEnd);

			// Creating a buffer of maximum size
			bytesAvailable = fileInputStream.available();
			bufferSize = Math.min(bytesAvailable, maxBufferSize);
			buffer = new byte[bufferSize];

			// Write the file into the form
			bytesRead = fileInputStream.read(buffer, 0, bufferSize);
			while (bytesRead > 0) {
				dos.write(buffer, 0, bufferSize);
				bytesAvailable = fileInputStream.available();
				bufferSize = Math.min(bytesAvailable, maxBufferSize);
				bytesRead = fileInputStream.read(buffer, 0, bufferSize);
			}

			// Attach the necessary multipart form data after file data...
			dos.writeBytes(lineEnd);
			dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);

			// Close streams
			fileInputStream.close();
			dos.flush();
			dos.close();

			// Get the response from the server to check if the file was copied
			inStream = new DataInputStream(conn.getInputStream());
			String str;
			while ((str = inStream.readLine()) != null) {
				if (str.equalsIgnoreCase("done")) {
					logger.info("Transfer complete.");
					inStream.close();
					return true;
				} else
					return false;
			}
			inStream.close();
			return false;
		} catch (IOException e) {
			logger.error("Error occured while transferring the KML file!", e);
			return false;
		}
	}

	@Override
	public UpdateContainer undoIt(VWorkspace vWorkspace) {
		// TODO Auto-generated method stub
		return null;
	}

}