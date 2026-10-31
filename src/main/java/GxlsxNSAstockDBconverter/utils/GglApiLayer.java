package main.java.GxlsxNSAstockDBconverter.utils;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.apache.v2.ApacheHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.Credentials;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.ServiceAccountCredentials;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

import static java.lang.System.exit;

public class GglApiLayer {
    private String fileId;
    private String fileName;
    private final String serviceAccountKey;
    private static final int MAX_RETRIES = 3;

    public GglApiLayer(String fileName, String serviceAccountKey) {
        this.fileId = null;
        this.fileName = fileName;
        this.serviceAccountKey = serviceAccountKey;
    }

    public boolean checkConnection() {
        int retryCount = 0;
        while (retryCount < MAX_RETRIES) {
            try {
                Drive service = getDriveService();
                FileList result = service.files().list()
                        .setPageSize(10)
                        .setFields("nextPageToken, files(id, name)")
                        .execute();
                Logger.LOG("✅ Connection OK. " + result.getFiles().size() + " files found:");
                for (File f : result.getFiles()) {
                    Logger.LOG("\t-File->\t" + f.getId() + "(" + f.getName() + ")");
                    if (f.getName().toUpperCase().contains(fileName.toUpperCase())) {
                        this.fileName = f.getName();
                        this.fileId = f.getId();
                    }
                }
                return true;
            } catch (GoogleJsonResponseException e) {
                int code = e.getStatusCode();
                Logger.ERROR_printMsg("HTTP code: " + code, e.getMessage());
                if (code == 401 || code == 403) return false;
                retryCount++;
            } catch (IOException | GeneralSecurityException e) {
                Logger.ERROR_printStacktrace("retryCount (" + (retryCount + 1) + "/" + MAX_RETRIES + ") ", e.getStackTrace());
                retryCount++;
            } catch (NoClassDefFoundError e) {
                try {
                    for (StackTraceElement ste : e.getStackTrace()) {
                        String className = ste.getClassName();
                        ClassLoader cl = Thread.currentThread().getContextClassLoader();
                        Class<?> clazz = (cl != null)
                                ? Class.forName(className, false, cl)
                                : Class.forName(className);

                        Logger.ERROR_printStacktrace(
                                "----BEGIN----"
                                + "\nclass : (" + className + ") from: " + clazz.getProtectionDomain().getCodeSource().getLocation()
                                + "\nError: " + e.getMessage()
                                + "\nStack trace: ",
                                e.getStackTrace());
                        exit(1);
                    }
                } catch (Exception e1) {
                    Logger.ERROR_printStacktrace("Exception in exception", e.getStackTrace());
                    exit(1);
                }
            }
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    public Spreadsheet downloadFile() throws IOException, GeneralSecurityException {
////        //TODO remove after test
//        FileInputStream fis = new FileInputStream(new java.io.File("debug.xlsx"));
//        ByteArrayOutputStream bos = new ByteArrayOutputStream();
//        bos.writeBytes(fis.readAllBytes());
//        Spreadsheet sp = new Spreadsheet("debug.xlsx", bos);
//        return sp;


        int retryCount = 0;
        while (retryCount < MAX_RETRIES) {
            try {
                Drive service = getDriveService();

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                if (fileId == null) {
                    Logger.ERROR_printStacktrace("file not found (" + fileName + ") on the specified GDrive. Please check configuration file.", null);
                    exit (2);
                }

                service.files().get(fileId).executeMediaAndDownloadTo(outputStream);

/*                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                service.files()
                        .export(fileId, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .executeMediaAndDownloadTo(outputStream);*/
                return new Spreadsheet(fileName, outputStream);
            } catch (GoogleJsonResponseException e) {
                int code = e.getStatusCode();
                Logger.ERROR_printMsg("HTTP (code " + code + ").", e.getMessage());
                if (code == 401 || code == 403) throw e;
                retryCount++;
            } catch (IOException | GeneralSecurityException e) {
                Logger.ERROR_printMsg("IO error. Retry (" + (retryCount + 1) + "/" + MAX_RETRIES + ").", e.getMessage());
                retryCount++;
            }
            try { Thread.sleep(2000); } catch (InterruptedException ie) {
                throw new IOException("Download interrupted", ie);
            }
        }
        throw new IOException("Fail after " + MAX_RETRIES + " tries");
    }

    public void uploadFile(ByteArrayOutputStream spreadsheet) throws IOException, GeneralSecurityException {
        Logger.LOG("Upload file");

        Drive driveService = getDriveService();
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(spreadsheet.toByteArray());
        InputStreamContent mediaContent = new InputStreamContent(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                byteArrayInputStream);

        File fileMetadata = new File();
        fileMetadata.setName(fileName);
        fileMetadata.setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        driveService.files().update(fileId, fileMetadata, mediaContent)
                .setFields("id, name, mimeType")
                .execute();

//
//        Drive service = getDriveService();
//        File gFile = new File();
//        gFile.setName(fileName);
//        File existingFileMetadata = service.files().get(fileId).setFields("*").execute();
//        Logger.LOG("Existing metadata: " + existingFileMetadata);
//        gFile.setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
//
//        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(spreadsheet.toByteArray());
//        InputStreamContent mediaContent = new InputStreamContent(
//                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
//                byteArrayInputStream
//        );
//
//        try {
//            File updatedFile = service.files().update(fileId, gFile, mediaContent)
//                    .setFields("id, name, mimeType")
//                    .execute();
//        Logger.LOG("Update returns: " + updatedFile);
//
//            try (FileOutputStream fos = new FileOutputStream("up_debug.xlsx")) {
//                fos.write(spreadsheet.toByteArray());
//            }
//        } catch (GoogleJsonResponseException e) {
//        Logger.ERROR_printMsg("Failed to upload file. ", e.getDetails().getMessage());
//            throw new IOException("Failed to upload file: " + e.getDetails().getMessage(), e);
//        }
//        try { Thread.sleep(2000); } catch (InterruptedException ie) {
//            throw new IOException("Téléchargement interrompu", ie);
//        }
        Logger.LOG("File with ID: " + fileId + " updated");
    }

    private Drive getDriveService() throws IOException, GeneralSecurityException {
        // Transport HTTP Apache (tu as déjà les jars httpclient/httpcore/commons-logging)
        HttpTransport httpTransport = new ApacheHttpTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();


        // 1) ServiceAccountCredentials brut depuis le JSON
        ServiceAccountCredentials rawSa = ServiceAccountCredentials
                .fromStream(new FileInputStream(serviceAccountKey));

        // 2) On lui applique le scope -> type retourné = GoogleCredentials
        Credentials scopedCreds = rawSa.createScoped(
                Collections.singleton(DriveScopes.DRIVE)
        );

        return new Drive.Builder(httpTransport, jsonFactory, new HttpCredentialsAdapter(scopedCreds))
                .setApplicationName("GxlsxNSAstockDBconverter")
                .build();
    }
}
