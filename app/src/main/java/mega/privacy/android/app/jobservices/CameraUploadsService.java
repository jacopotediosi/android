package mega.privacy.android.app.jobservices;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.MediaStore;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import mega.privacy.android.app.DatabaseHandler;
import mega.privacy.android.app.MegaApplication;
import mega.privacy.android.app.MegaPreferences;
import mega.privacy.android.app.MimeTypeList;
import mega.privacy.android.app.R;
import mega.privacy.android.app.UserCredentials;
import mega.privacy.android.app.VideoCompressor;
import mega.privacy.android.app.lollipop.ManagerActivityLollipop;
import mega.privacy.android.app.lollipop.megachat.ChatSettings;
import mega.privacy.android.app.utils.Constants;
import mega.privacy.android.app.utils.FileUtil;
import mega.privacy.android.app.utils.PreviewUtils;
import mega.privacy.android.app.utils.ThumbnailUtils;
import mega.privacy.android.app.utils.Util;
import mega.privacy.android.app.utils.conversion.VideoCompressionCallback;
import nz.mega.sdk.MegaApiAndroid;
import nz.mega.sdk.MegaApiJava;
import nz.mega.sdk.MegaChatApi;
import nz.mega.sdk.MegaChatApiAndroid;
import nz.mega.sdk.MegaChatApiJava;
import nz.mega.sdk.MegaChatError;
import nz.mega.sdk.MegaChatRequest;
import nz.mega.sdk.MegaChatRequestListenerInterface;
import nz.mega.sdk.MegaError;
import nz.mega.sdk.MegaNode;
import nz.mega.sdk.MegaRequest;
import nz.mega.sdk.MegaRequestListenerInterface;
import nz.mega.sdk.MegaTransfer;
import nz.mega.sdk.MegaTransferListenerInterface;

import static mega.privacy.android.app.jobservices.SyncRecord.STATUS_PENDING;
import static mega.privacy.android.app.jobservices.SyncRecord.STATUS_TO_COMPRESS;
import static mega.privacy.android.app.lollipop.managerSections.SettingsFragmentLollipop.VIDEO_QUALITY_MEDIUM;

public class CameraUploadsService extends JobService implements MegaChatRequestListenerInterface, MegaRequestListenerInterface, MegaTransferListenerInterface, VideoCompressionCallback {

    public static String PHOTO_SYNC = "PhotoSync";
    public static String CAMERA_UPLOADS = "Camera Uploads";
    public static String SECONDARY_UPLOADS = "Media Uploads";
    private String ERROR_NOT_ENOUGH_SPACE = "ERROR_NOT_ENOUGH_SPACE";
    private String ERROR_CREATE_FILE_IO_ERROR = "ERROR_CREATE_FILE_IO_ERROR";
    private String ERROR_SOURCE_FILE_NOT_EXIST = "SOURCE_FILE_NOT_EXIST";

    private NotificationCompat.Builder mBuilder;
    NotificationManager mNotificationManager;

    private int notificationId = Constants.NOTIFICATION_CAMERA_UPLOADS;
    private String notificationChannelId = Constants.NOTIFICATION_CHANNEL_CAMERA_UPLOADS_ID;
    private String notificationChannelName = Constants.NOTIFICATION_CHANNEL_CAMERA_UPLOADS_NAME;

    Thread task;

    static public boolean running = false;
    private Handler handler;

    WifiManager.WifiLock lock;
    PowerManager.WakeLock wl;

    private boolean isOverquota = false;
    private boolean canceled;

    DatabaseHandler dbH;

    MegaPreferences prefs;
    String localPath = "";
    ChatSettings chatSettings;
    long cameraUploadHandle = -1;
    boolean secondaryEnabled = false;
    String localPathSecondary = "";
    long secondaryUploadHandle = -1;
    MegaNode secondaryUploadNode = null;

    boolean isLoggingIn = false;

    MegaApiAndroid megaApi;
    MegaChatApiAndroid megaChatApi;
    MegaApplication app;

    JobParameters globalParams;

    int LOGIN_IN = 12;

    static CameraUploadsService cameraUploadsService;
    static String gSession;

    public class Media {
        public String filePath;
        public long timestamp;
    }

    Queue<Media> cameraFiles = new LinkedList<>();
    Queue<Media> primaryVideos = new LinkedList<>();
    Queue<Media> secondaryVideos = new LinkedList<>();
    ArrayList<SyncRecord> pendingUploadsList = new ArrayList<>();
    ArrayList<SyncRecord> pendingUploadsListSecondary = new ArrayList<>();
    ArrayList<SyncRecord> pendingVideoUploadsList = new ArrayList<>();
    ArrayList<SyncRecord> pendingVideoUploadsListSecondary = new ArrayList<>();
    Queue<Media> mediaFilesSecondary = new LinkedList<>();
    MegaNode cameraUploadNode = null;
    private int totalUploaded;
    private int totalToUpload;

    long currentTimeStamp = 0;
    long secondaryTimeStamp = 0;
    Notification mNotification;
    Intent mIntent;
    PendingIntent mPendingIntent;
    private String tempRoot;
    Context mContext;
    private VideoCompressor mVideoCompressor;

    @Override
    public boolean onStartJob(JobParameters params) {
        log("onStartJob");
        cameraUploadsService = this;
        globalParams = params;
        handler = new Handler();

        try {
            log("Start service here");
            initService();
            task = new Thread() {
                @Override
                public void run() {
                    try {
                        int result = shouldRun();

                        if (result == 0) {
                            startCameraUploads();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                    }
                }
            };

            task.start();
            return true;
        } catch (Exception e) {
            log("CameraUploadsService Exception: " + e.getMessage() + "_" + e.getStackTrace());
        }

        return false;
    }

    private String getTempStorageRoot(String folderName) {
        String rootPath = Environment.getExternalStorageDirectory() + File.separator + folderName + File.separator;
        File root = new File(rootPath);
        if (!root.exists()) {
            if (!root.mkdirs()) {
                return null;
            }
        }
        return rootPath;
    }

    private void startCameraUploads() {
        showNotification(getString(R.string.section_photo_sync),getString(R.string.settings_camera_notif_title),mPendingIntent);
        startForeground(notificationId,mNotification);
        try {
            getFilesFromMediaStore();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean shouldCompressVideo() {
        String qualitySetting = prefs.getUploadVideoQuality();
        if (qualitySetting != null && Integer.parseInt(qualitySetting) == VIDEO_QUALITY_MEDIUM) {
            return true;
        }
        return false;
    }

    private void extractMedia(Cursor cursorCamera,boolean isSecondary,boolean isVideo) {
        try {
            log("if (cursorCamera != null)");
            String path = isSecondary ? localPathSecondary : localPath;

            int dataColumn = cursorCamera.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
            int timestampColumn;
            if (cursorCamera.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED) == 0) {
                log("if(cursorCamera.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED) == 0)");
                timestampColumn = cursorCamera.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            } else {
                log("if(cursorCamera.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED) != 0)");
                timestampColumn = cursorCamera.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            }

            while (cursorCamera.moveToNext()) {

                Media media = new Media();
                media.filePath = cursorCamera.getString(dataColumn);
                media.timestamp = cursorCamera.getLong(timestampColumn) * 1000;

                log("while(cursorCamera.moveToNext()) - media.filePath: " + media.filePath + "_localPath: " + path);

                //Check files of the Camera Uploads
                if (checkFile(media,path)) {
                    log("if (checkFile(media," + path + "))");
                    if (isSecondary) {
                        if (isVideo) {
                            secondaryVideos.add(media);
                        } else {
                            mediaFilesSecondary.add(media);
                        }
                    } else {
                        if (isVideo) {
                            primaryVideos.add(media);
                        } else {
                            cameraFiles.add(media);
                        }
                    }
                    log("Camera Files added: " + media.filePath);
                }
            }
        } catch (Exception e) {
            log("Exception cursorSecondary:" + e.getMessage() + "-" + e.getStackTrace());
        }
    }

    private void getFilesFromMediaStore() {
        log("getFilesFromMediaStore()");
        if (!wl.isHeld()) {
            wl.acquire();
        }
        if (!lock.isHeld()) {
            lock.acquire();
        }

        String projection[] = {
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED
        };

        String selectionCamera = null;
        String selectionSecondary = null;
        String[] selectionArgs = null;
        prefs = dbH.getPreferences();

        if (prefs != null) {
            log("if (prefs != null)");
            if (prefs.getCamSyncTimeStamp() != null) {
                log("if (prefs.getCamSyncTimeStamp() != null)");
                currentTimeStamp = Long.parseLong(prefs.getCamSyncTimeStamp());
                selectionCamera = "(" + MediaStore.MediaColumns.DATE_MODIFIED + "*1000) > " + currentTimeStamp + " OR " + "(" + MediaStore.MediaColumns.DATE_ADDED + "*1000) > " + currentTimeStamp;
                log("SELECTION: " + selectionCamera);
            }
            if (secondaryEnabled) {
                log("if(secondaryEnabled)");
                if (prefs.getSecSyncTimeStamp() != null) {
                    log("if (prefs.getSecSyncTimeStamp() != null)");
                    secondaryTimeStamp = Long.parseLong(prefs.getSecSyncTimeStamp());
                    selectionSecondary = "(" + MediaStore.MediaColumns.DATE_MODIFIED + "*1000) > " + secondaryTimeStamp + " OR " + "(" + MediaStore.MediaColumns.DATE_ADDED + "*1000) > " + secondaryTimeStamp;
                    log("SELECTION SECONDARY: " + selectionSecondary);
                }
            }
        }

        String order = MediaStore.MediaColumns.DATE_MODIFIED + " ASC";
        ArrayList<Uri> uris = new ArrayList<>();
        if (prefs.getCamSyncFileUpload() == null) {
            log("if (prefs.getCamSyncFileUpload() == null)");
            dbH.setCamSyncFileUpload(MegaPreferences.ONLY_PHOTOS);
            uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            uris.add(MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        } else {
            log("if (prefs.getCamSyncFileUpload() != null)");
            switch (Integer.parseInt(prefs.getCamSyncFileUpload())) {
                case MegaPreferences.ONLY_PHOTOS: {
                    log("case MegaPreferences.ONLY_PHOTOS:");
                    uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                    uris.add(MediaStore.Images.Media.INTERNAL_CONTENT_URI);
                    break;
                }
                case MegaPreferences.ONLY_VIDEOS: {
                    log("case MegaPreferences.ONLY_VIDEOS:");
                    uris.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
                    uris.add(MediaStore.Video.Media.INTERNAL_CONTENT_URI);
                    break;
                }
                case MegaPreferences.PHOTOS_AND_VIDEOS: {
                    log("case MegaPreferences.PHOTOS_AND_VIDEOS:");
                    uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                    uris.add(MediaStore.Images.Media.INTERNAL_CONTENT_URI);
                    uris.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
                    uris.add(MediaStore.Video.Media.INTERNAL_CONTENT_URI);
                    break;
                }
            }
        }

        for (int i = 0;i < uris.size();i++) {
            Uri uri = uris.get(i);
            boolean isVideo = uri.equals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) || uri.equals(MediaStore.Images.Media.INTERNAL_CONTENT_URI);

            //Primary Media Folder
            Cursor cursorCamera = app.getContentResolver().query(uri,projection,selectionCamera,selectionArgs,order);
            if (cursorCamera != null) {
                extractMedia(cursorCamera,false,isVideo);
            }

            //Secondary Media Folder
            if (secondaryEnabled) {
                log("if(secondaryEnabled)");
                Cursor cursorSecondary = app.getContentResolver().query(uri,projection,selectionSecondary,selectionArgs,order);
                if (cursorSecondary != null) {
                    extractMedia(cursorSecondary,true,isVideo);
                }
            }
        }

        cameraUploadNode = megaApi.getNodeByHandle(cameraUploadHandle);
        if (cameraUploadNode == null) {
            log("ERROR: cameraUploadNode == null");
            finish();
            return;
        }

        totalUploaded = 0;
        prepareUpload(cameraFiles,mediaFilesSecondary,primaryVideos,secondaryVideos);
    }

    private void prepareUpload(Queue<Media> primaryList,Queue<Media> secondaryList,Queue<Media> primaryVideoList,Queue<Media> secondaryVideoList) {
        Log.d("Yuan","prepareUpload");
        pendingUploadsList = getPendingList(primaryList,false,false);
        saveDataToDB(pendingUploadsList);
        pendingVideoUploadsList = getPendingList(primaryVideoList,false,true);
        saveDataToDB(pendingVideoUploadsList);

        //secondary list
        if (secondaryEnabled) {
            pendingUploadsListSecondary = getPendingList(secondaryList,true,false);
            saveDataToDB(pendingUploadsListSecondary);
            pendingVideoUploadsListSecondary = getPendingList(secondaryVideoList,true,true);
            saveDataToDB(pendingVideoUploadsListSecondary);
        }

        //need to maintain timestamp for better performance
        updateTimeStamp();
        List<SyncRecord> finalList = dbH.findAllPendingSyncRecords();

        if (finalList.size() == 0) {
            if (isCompressedVideoPending()) {
                startVideoCompression();
            } else {
                Log.d("yuan","nothing to upload");
                finish();
                return;
            }
        } else {
            Log.d("yuan","got pending uploads " + finalList.size());
            startParallelUpload(finalList,false);
        }
    }

    private void startParallelUpload(List<SyncRecord> finalList,boolean isCompressedVideo) {
        for (SyncRecord file : finalList) {
            boolean isSec = file.isSecondary();
            MegaNode parent;
            if (isSec) {
                parent = secondaryUploadNode;
            } else {
                parent = cameraUploadNode;
            }
            if (file.getType() == SyncRecord.TYPE_PHOTO) {
                String newPath = createTempFile(file);
                //IOException occurs.
                if (ERROR_CREATE_FILE_IO_ERROR.equals(newPath)) {
                    continue;
                }
//            if("space isn't enough!".equals(newPath)) {
//                TODO show dialog.
//                return;
//            }
                while ("space isn't enough!".equals(newPath)) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (megaApi.getNumPendingUploads() == 0) {
                        //TODO show dialog.
                        return;
                    }
                    newPath = createTempFile(file);
                }
                if (!newPath.equals(file.getNewPath())) {
                    file.setNewPath(newPath);
                }
            }

            totalToUpload++;
            String path;
            if (isCompressedVideo || file.getType() == SyncRecord.TYPE_PHOTO) {
                path = file.getNewPath();
            } else {
                path = file.getLocalPath();
            }
            
            if (file.isCopyOnly()) {
                megaApi.copyNode(megaApi.getNodeByHandle(file.getNodeHandle()),parent,file.getFileName(),this);
            } else {
                megaApi.startUpload(path,parent,file.getFileName(),this);
            }
        }
    }

    private void saveDataToDB(ArrayList<SyncRecord> list) {
        for (SyncRecord file : list) {
            boolean isSec = file.isSecondary();
            MegaNode parent;
            if (isSec) {
                parent = secondaryUploadNode;
            } else {
                parent = cameraUploadNode;
            }

            if (file.isCopyOnly()) {
                //file exist in other location, server will copy internally
            } else {
                File f = new File(file.getLocalPath());
                if (!f.exists()) {
                    dbH.deleteSyncRecordByLocalPath(file.getLocalPath());
                    continue;
                }
            }

            String fileName;
            if (Boolean.parseBoolean(prefs.getKeepFileNames())) {
                //Keep the file names as device
                fileName = file.getFileName();
            } else {
                int photoIndex = 0;
                boolean inCloud;
                boolean inDatabase;
                do {
                    fileName = Util.getPhotoSyncNameWithIndex(file.getTimestamp(),file.getLocalPath(),photoIndex);
                    photoIndex++;

                    inCloud = megaApi.getChildNode(parent,fileName) != null;
                    inDatabase = dbH.fileNameExists(fileName,isSec,SyncRecord.TYPE_ANY);
                } while ((inCloud || inDatabase));
            }
            file.setFileName(fileName);
            file.setNewPath(tempRoot + fileName);
            dbH.saveSyncRecord(file);
        }
    }

    private void onQueueComplete() {
        log("onQueueComplete");
        log("Stopping foreground!");

        megaApi.resetTotalUploads();
        totalUploaded = 0;
        totalToUpload = 0;

        if ((lock != null) && (lock.isHeld()))
            try {
                lock.release();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        if ((wl != null) && (wl.isHeld()))
            try {
                wl.release();
            } catch (Exception ex) {
                ex.printStackTrace();
            }

        if (totalUploaded == 0) {
            log("TotalUploaded == 0");
        } else {
            log("stopping service!");
        }

        finish();
        stopForeground(true);
        if (mNotificationManager != null) {
            mNotificationManager.cancel(notificationId);
        }
        jobFinished(globalParams,true);
    }

    private ArrayList<SyncRecord> getPendingList(Queue<Media> mediaList,boolean isSecondary,boolean isVideo) {
        ArrayList<SyncRecord> pendingList = new ArrayList<>();
        MegaNode uploadNode;
        if (isSecondary) {
            uploadNode = megaApi.getNodeByHandle(secondaryUploadHandle);
        } else {
            uploadNode = megaApi.getNodeByHandle(cameraUploadHandle);
        }
        long uploadNodeHandle = uploadNode.getHandle();
        int type = isVideo ? SyncRecord.TYPE_VIDEO : SyncRecord.TYPE_PHOTO;

        while (mediaList.size() > 0) {
            log("if (mediaList.size() > 0)");
            final Media media = mediaList.poll();
            if (dbH.localPathExists(localPath,isSecondary,SyncRecord.TYPE_ANY)) {
                continue;
            }
            File file = new File(media.filePath);
            String localFingerPrint = megaApi.getFingerprint(media.filePath);

            MegaNode nodeExists;
            //Source file
            File sourceFile = new File(media.filePath);

            nodeExists = megaApi.getNodeByFingerprint(localFingerPrint,uploadNode);
            if (nodeExists == null) {
                log("if(nodeExists == null)");
                //Check if the file is already uploaded in the correct folder but without a fingerprint
                int photoIndex = 0;
                MegaNode possibleNode = null;
                String photoFinalName;
                do {
                    //Iterate between all files with the correct target name

                    //Create the final name taking into account the
                    if (Boolean.parseBoolean(prefs.getKeepFileNames())) {
                        //Keep the file names as device

                        photoFinalName = media.filePath;
                        log("Keep the camera file name: " + photoFinalName);
                    } else {
                        photoFinalName = Util.getPhotoSyncNameWithIndex(media.timestamp,media.filePath,photoIndex);
                        log("CHANGE the camera file name: " + photoFinalName);
                    }

                    possibleNode = megaApi.getChildNode(uploadNode,photoFinalName);

                    // If the file matches name, mtime and size, and doesn't have a fingerprint,
                    // => we consider that it's the correct one
                    if (possibleNode != null &&
                            sourceFile.length() == possibleNode.getSize() &&
                            megaApi.getFingerprint(possibleNode) == null) {
                        nodeExists = possibleNode;
                        log("nodeExists = possibleNode;");
                        break;
                    }
                    //Continue iterating
                    photoIndex++;
                } while (possibleNode != null);

                if (nodeExists == null) {
                    log("if(nodeExists == null)");
                    // If the file wasn't found by fingerprint nor in the destination folder,
                    // take a look in the folder from v1
                    SharedPreferences prefs = this.getSharedPreferences("prefs_main.xml",0);
                    if (prefs != null) {
                        String handle = prefs.getString("camera_sync_folder_hash",null);
                        if (handle != null) {
                            MegaNode prevFolder = megaApi.getNodeByHandle(MegaApiAndroid.base64ToHandle(handle));
                            if (prevFolder != null) {
                                // If we reach this code, the app is an updated v1 and the previously selected
                                // folder still exists

                                // If the file matches name, mtime and size, and doesn't have a fingerprint,
                                // => we consider that it's the correct one
                                possibleNode = megaApi.getChildNode(prevFolder,sourceFile.getName());
                                if (possibleNode != null &&
                                        sourceFile.length() == possibleNode.getSize() &&
                                        megaApi.getFingerprint(possibleNode) == null) {
                                    nodeExists = possibleNode;
                                }
                            }
                        }
                    }
                }
            }

            if (nodeExists == null) {
                log("UPLOAD THE FILE: " + media.filePath);
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(media.timestamp);
                log("YYYY-MM-DD HH.MM.SS -- " + cal.get(Calendar.YEAR) + "-" + cal.get(Calendar.MONTH) + "-" + cal.get(Calendar.DAY_OF_MONTH) + " " + cal.get(Calendar.HOUR_OF_DAY) + "." + cal.get(Calendar.MINUTE) + "." + cal.get(Calendar.SECOND));
                boolean photoAlreadyExists = false;
                ArrayList<MegaNode> nL = megaApi.getChildren(uploadNode,MegaApiJava.ORDER_ALPHABETICAL_ASC);
                for (int i = 0;i < nL.size();i++) {
                    if ((nL.get(i).getName().compareTo(Util.getPhotoSyncName(media.timestamp,media.filePath)) == 0) && (nL.get(i).getSize() == file.length())) {
                        photoAlreadyExists = true;
                    }
                }

                if (!photoAlreadyExists) {
                    log("if (!photoAlreadyExists)");
                    SyncRecord record = new SyncRecord(file.getAbsolutePath(),file.getName(),media.timestamp,isSecondary,type);
                    if (shouldCompressVideo() && type == SyncRecord.TYPE_VIDEO) {
                        record.setStatus(STATUS_TO_COMPRESS);
                    }
                    record.setOriginFingerprint(localFingerPrint);
                    pendingList.add(record);
                    log("MediaFinalName: " + file.getName());
                }
            } else {
                log("NODE EXISTS: " + megaApi.getParentNode(nodeExists).getName() + " : " + nodeExists.getName());
                if (megaApi.getParentNode(nodeExists).getHandle() != uploadNodeHandle) {
                    SyncRecord record = new SyncRecord(nodeExists.getHandle(),file.getName(),true,media.filePath,media.timestamp,isSecondary,type);
                    record.setOriginFingerprint(nodeExists.getFingerprint());
                    pendingList.add(record);
                    log("MediaFinalName: " + file.getName());
                } else {
                    if (!(Boolean.parseBoolean(prefs.getKeepFileNames()))) {
                        //Change the file names as device
                        log("Call Look for Rename Task");
                        final MegaNode existingNode = nodeExists;
                        final MegaNode parentNode = uploadNode;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                new LookForRenameTask(media,parentNode).rename(existingNode);
                            }
                        });
                    }
                }
            }
        }
        return pendingList;
    }

    private class LookForRenameTask {
        Media media;
        String photoFinalName;
        MegaNode uploadNode;

        public LookForRenameTask(Media media,MegaNode uploadNode) {
            this.media = media;
            this.uploadNode = uploadNode;
        }

        protected Boolean rename(MegaNode nodeExists) {

            File file = new File(media.filePath);
            log("RENOMBRAR EL FICHERO: " + media.filePath);
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(media.timestamp);
            log("YYYY-MM-DD HH.MM.SS -- " + cal.get(Calendar.YEAR) + "-" + cal.get(Calendar.MONTH) + "-" + cal.get(Calendar.DAY_OF_MONTH) + " " + cal.get(Calendar.HOUR_OF_DAY) + "." + cal.get(Calendar.MINUTE) + "." + cal.get(Calendar.SECOND));
            boolean photoAlreadyExists = false;
            ArrayList<MegaNode> nL = megaApi.getChildren(uploadNode,MegaApiJava.ORDER_ALPHABETICAL_ASC);
            for (int i = 0;i < nL.size();i++) {
                if ((nL.get(i).getName().compareTo(Util.getPhotoSyncName(media.timestamp,media.filePath)) == 0) && (nL.get(i).getSize() == file.length())) {
                    photoAlreadyExists = true;
                }
            }

            if (!photoAlreadyExists) {
                int photoIndex = 0;
                this.photoFinalName = null;
                do {
                    photoFinalName = Util.getPhotoSyncNameWithIndex(media.timestamp,media.filePath,photoIndex);
                    photoIndex++;
                } while (megaApi.getChildNode(uploadNode,photoFinalName) != null);

                log("photoFinalName: " + photoFinalName + "______" + photoIndex);

                megaApi.renameNode(nodeExists,photoFinalName,cameraUploadsService);
                log("RENAMED!!!! MediaFinalName: " + photoFinalName + "______" + photoIndex);

                return true;
            } else {
                return false;
            }
        }
    }

    private boolean checkFile(Media media,String path) {

        if (media.filePath != null) {
            if (path != null) {
                if (path.compareTo("") != 0) {
                    if (media.filePath.startsWith(path)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private int shouldRun() {
        log("shouldRun()");

        if (!Util.isOnline(this)) {
            log("Not online");
            finish();
            return 1;
        }

        prefs = dbH.getPreferences();
        if (prefs == null) {
            log("Not defined, so not enabled");
            finish();
            return 2;
        } else {
            if (prefs.getCamSyncEnabled() == null) {
                log("Not defined, so not enabled");
                finish();
                return 3;
            } else {
                if (!Boolean.parseBoolean(prefs.getCamSyncEnabled())) {
                    log("Camera Sync Not enabled");
                    finish();
                    return 4;
                } else {
                    if (prefs.getCameraFolderExternalSDCard() != null && Boolean.parseBoolean(prefs.getCameraFolderExternalSDCard())) {
                        Uri uri = Uri.parse(prefs.getUriExternalSDCard());
                        localPath = FileUtil.getFullPathFromTreeUri(uri,this);
                    } else {
                        localPath = prefs.getCamSyncLocalPath();
                    }

                    if (localPath == null) {
                        log("Not defined, so not enabled");
                        finish();
                        return 5;
                    } else {
                        if ("".compareTo(localPath) == 0) {
                            log("Not defined, so not enabled");
                            finish();
                            return 6;

                        } else {
                            log("Localpath: " + localPath);
                        }
                    }

                    boolean isWifi = Util.isOnWifi(this);
                    if (prefs.getCamSyncWifi() == null) {
                        if (!isWifi) {
                            log("no wifi...");
                            finish();
                            return 7;
                        }
                    } else {
                        if (Boolean.parseBoolean(prefs.getCamSyncWifi())) {
                            if (!isWifi) {
                                log("no wifi...");
                                finish();
                                return 8;
                            }
                        }
                    }

                    UserCredentials credentials = dbH.getCredentials();
                    if (credentials == null) {
                        log("There are not user credentials");
                        finish();
                        return 11;
                    }

                    gSession = credentials.getSession();

                    if (megaApi.getRootNode() == null) {
                        log("RootNode = null");

                        running = true;

                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (megaApi.getRootNode() == null) {
                                    isLoggingIn = MegaApplication.isLoggingIn();
                                    if (!isLoggingIn) {

                                        isLoggingIn = true;
                                        MegaApplication.setLoggingIn(isLoggingIn);

                                        if (Util.isChatEnabled()) {
                                            log("shouldRun: Chat is ENABLED");
                                            if (megaChatApi == null) {
                                                megaChatApi = ((MegaApplication)getApplication()).getMegaChatApi();
                                            }

                                            int ret = megaChatApi.getInitState();

                                            if (ret == 0 || ret == MegaChatApi.INIT_ERROR) {
                                                ret = megaChatApi.init(gSession);
                                                log("shouldRun: result of init ---> " + ret);
                                                chatSettings = dbH.getChatSettings();
                                                if (ret == MegaChatApi.INIT_NO_CACHE) {
                                                    log("shouldRun: condition ret == MegaChatApi.INIT_NO_CACHE");

                                                } else if (ret == MegaChatApi.INIT_ERROR) {
                                                    log("shouldRun: condition ret == MegaChatApi.INIT_ERROR");
                                                    if (chatSettings == null) {
                                                        log("1 - shouldRun: ERROR----> Switch OFF chat");
                                                        chatSettings = new ChatSettings();
                                                        chatSettings.setEnabled(false + "");
                                                        dbH.setChatSettings(chatSettings);
                                                    } else {
                                                        log("2 - shouldRun: ERROR----> Switch OFF chat");
                                                        dbH.setEnabledChat(false + "");
                                                    }
                                                    megaChatApi.logout(cameraUploadsService);
                                                } else {
                                                    log("shouldRun: Chat correctly initialized");
                                                }
                                            }
                                        }

                                        megaApi.fastLogin(gSession,cameraUploadsService);
                                    } else {
                                        log("Another login is processing");
                                    }
                                } else {
                                    log("postDelayed RootNode != null");

                                    int r = runLoggedIn();
                                    log("shouldRunAfterLoginDelayed -> " + r);
                                    if (r == 0) {
                                        startCameraUploads();
                                    }
                                }
                            }
                        },30000);

                        return LOGIN_IN;
                    }

                    log("RootNode != null");
                    int r = runLoggedIn();
                    return r;
                }
            }
        }
    }

    private int runLoggedIn() {

        if (prefs.getCamSyncHandle() == null) {
            log("if (prefs.getCamSyncHandle() == null)");
            cameraUploadHandle = -1;
        } else {
            log("if (prefs.getCamSyncHandle() != null)");
            cameraUploadHandle = Long.parseLong(prefs.getCamSyncHandle());
        }

        if (prefs.getSecondaryMediaFolderEnabled() == null) {
            log("if (prefs.getSecondaryMediaFolderEnabled() == null)");
            dbH.setSecondaryUploadEnabled(false);
            log("Not defined, so not enabled");
            secondaryEnabled = false;
        } else {
            log("if (prefs.getSecondaryMediaFolderEnabled() != null)");
            if (!Boolean.parseBoolean(prefs.getSecondaryMediaFolderEnabled())) {
                log("Not enabled Secondary");
                secondaryEnabled = false;
            } else {
                secondaryEnabled = true;
                localPathSecondary = prefs.getLocalPathSecondaryFolder();
            }
        }

        ArrayList<MegaNode> nl = megaApi.getChildren(megaApi.getRootNode());
        if (cameraUploadHandle == -1) {
            log("Find the Camera Uploads folder of the old PhotoSync");
            for (int i = 0;i < nl.size();i++) {
                if ((CAMERA_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                    cameraUploadHandle = nl.get(i).getHandle();
                    dbH.setCamSyncHandle(cameraUploadHandle);
                } else if ((PHOTO_SYNC.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                    cameraUploadHandle = nl.get(i).getHandle();
                    dbH.setCamSyncHandle(cameraUploadHandle);
                    megaApi.renameNode(nl.get(i),CAMERA_UPLOADS,this);
                }
            }

            log("If not Camera Uploads nor Photosync");
            if (cameraUploadHandle == -1) {
                log("must create the folder");
                megaApi.createFolder(CAMERA_UPLOADS,megaApi.getRootNode(),this);
                return 13;
            }
        } else {
            MegaNode n = megaApi.getNodeByHandle(cameraUploadHandle);
            if (n == null) {
                log("Node with cameraUploadHandle is not NULL");
                cameraUploadHandle = -1;
                for (int i = 0;i < nl.size();i++) {
                    if ((CAMERA_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                        cameraUploadHandle = nl.get(i).getHandle();
                        dbH.setCamSyncHandle(cameraUploadHandle);
                    } else if ((PHOTO_SYNC.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                        cameraUploadHandle = nl.get(i).getHandle();
                        dbH.setCamSyncHandle(cameraUploadHandle);
                        megaApi.renameNode(nl.get(i),CAMERA_UPLOADS,this);
                    }
                }

                if (cameraUploadHandle == -1) {
                    log("If not Camera Uploads nor Photosync--- must create the folder");
                    megaApi.createFolder(CAMERA_UPLOADS,megaApi.getRootNode(),this);
                    return 14;
                }
            } else {
                log("Sync Folder " + cameraUploadHandle + " Node: " + n.getName());
            }
        }

        if (secondaryEnabled) {
            log("the secondary uploads are enabled");
            String temp = prefs.getMegaHandleSecondaryFolder();
            if (temp != null) {
                if (temp.compareTo("") != 0) {
                    secondaryUploadHandle = Long.parseLong(prefs.getMegaHandleSecondaryFolder());
                    if (secondaryUploadHandle == -1) {
                        for (int i = 0;i < nl.size();i++) {
                            if ((SECONDARY_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                                secondaryUploadHandle = nl.get(i).getHandle();
                                dbH.setSecondaryFolderHandle(secondaryUploadHandle);
                            }
                        }

                        //If not "Media Uploads"
                        if (secondaryUploadHandle == -1) {
                            log("must create the secondary folder");
                            megaApi.createFolder(SECONDARY_UPLOADS,megaApi.getRootNode(),this);
                            return 15;
                        }
                    } else {
                        log("SecondaryUploadHandle: " + secondaryUploadHandle);
                        MegaNode n = megaApi.getNodeByHandle(secondaryUploadHandle);
                        //If ERROR with the handler (the node may no longer exist): Create the folder Media Uploads
                        if (n == null) {
                            secondaryUploadHandle = -1;
                            log("The secondary media folder may not longer exists");
                            for (int i = 0;i < nl.size();i++) {
                                if ((SECONDARY_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                                    secondaryUploadHandle = nl.get(i).getHandle();
                                    dbH.setSecondaryFolderHandle(secondaryUploadHandle);
                                }
                            }

                            //If not "Media Uploads"
                            if (secondaryUploadHandle == -1) {
                                log("must create the folder");
                                megaApi.createFolder(SECONDARY_UPLOADS,megaApi.getRootNode(),this);
                                return 16;
                            }
                        } else {
                            log("Secondary Folder " + secondaryUploadHandle + " Node: " + n.getName());
                            secondaryUploadNode = megaApi.getNodeByHandle(secondaryUploadHandle);
                        }
                    }
                } else {
                    //If empty string as SecondaryHandle
                    secondaryUploadHandle = -1;
                    for (int i = 0;i < nl.size();i++) {
                        if ((SECONDARY_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                            secondaryUploadHandle = nl.get(i).getHandle();
                            dbH.setSecondaryFolderHandle(secondaryUploadHandle);
                        }
                    }

                    //If not "Media Uploads"
                    if (secondaryUploadHandle == -1) {
                        log("must create the folder");
                        megaApi.createFolder(SECONDARY_UPLOADS,megaApi.getRootNode(),this);
                        return 17;
                    }
                }
            } else {
                for (int i = 0;i < nl.size();i++) {
                    if ((SECONDARY_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                        secondaryUploadHandle = nl.get(i).getHandle();
                        dbH.setSecondaryFolderHandle(secondaryUploadHandle);
                    }
                }

                //If not "Media Uploads"
                if (secondaryUploadHandle == -1) {
                    log("must create the folder");
                    megaApi.createFolder(SECONDARY_UPLOADS,megaApi.getRootNode(),this);
                    return 18;
                }
            }
        } else {
            log("Secondary NOT Enabled");
        }

        return 0;
    }

    private void initService() {
        log("initService()");
        mContext = getApplicationContext();
        totalUploaded = 0;
        totalToUpload = 0;
        canceled = false;
        isOverquota = false;

        try {
            app = (MegaApplication)getApplication();
        } catch (Exception ex) {
            finish();
        }

        megaApi = app.getMegaApi();
        megaChatApi = app.getMegaChatApi();

        if (megaApi == null) {
            finish();
            return;
        }

        int wifiLockMode = WifiManager.WIFI_MODE_FULL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
            wifiLockMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        }

        WifiManager wifiManager = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);
        lock = wifiManager.createWifiLock(wifiLockMode,"MegaDownloadServiceWifiLock");
        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MegaDownloadServicePowerLock:");

        dbH = DatabaseHandler.getDbHandler(mContext);

        mNotificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);

        String previousIP = app.getLocalIpAddress();
        String currentIP = Util.getLocalIpAddress();
        if (previousIP == null || (previousIP.length() == 0) || (previousIP.compareTo("127.0.0.1") == 0)) {
            app.setLocalIpAddress(currentIP);
        } else if ((currentIP != null) && (currentIP.length() != 0) && (currentIP.compareTo("127.0.0.1") != 0) && (currentIP.compareTo(previousIP) != 0)) {
            app.setLocalIpAddress(currentIP);
            log("reconnect");
            megaApi.reconnect();
        }

        mIntent = new Intent(this,ManagerActivityLollipop.class);
        mIntent.setAction(Constants.ACTION_CANCEL_CAM_SYNC);
        mPendingIntent = PendingIntent.getActivity(this,0,mIntent,0);
        tempRoot = mContext.getCacheDir().toString() + File.separator;
        File root = new File(tempRoot);
        if(!root.exists()) {
            root.mkdirs();
        }
    }

    private void finish() {
        log("finish CameraUploadsService");

        if (running) {
            handler.removeCallbacksAndMessages(null);
            running = false;
        }
        cancel();
    }

    private void cancel() {
        if ((lock != null) && (lock.isHeld()))
            try {
                lock.release();
            } catch (Exception ex) {
            }
        if ((wl != null) && (wl.isHeld()))
            try {
                wl.release();
            } catch (Exception ex) {
            }

        if (isOverquota) {
            showStorageOverQuotaNotification();
        }

        canceled = true;
        running = false;
        stopForeground(true);
        if (mNotificationManager != null) {
            Log.d("Yuan","cancelling notification id is " + notificationChannelId);
            mNotificationManager.cancel(notificationId);
        } else {
            Log.d("Yuan","no notification to cancel");
        }
        jobFinished(globalParams,true);
    }

    private void showStorageOverQuotaNotification() {
        log("showStorageOverQuotaNotification");

        String contentText = getString(R.string.download_show_info);
        String message = getString(R.string.overquota_alert_title);

        Intent intent = new Intent(this,ManagerActivityLollipop.class);
        intent.setAction(Constants.ACTION_OVERQUOTA_STORAGE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(notificationChannelId,notificationChannelName,NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(true);
            channel.setSound(null,null);
            mNotificationManager.createNotificationChannel(channel);

            NotificationCompat.Builder mBuilderCompatO = new NotificationCompat.Builder(mContext,notificationChannelId);

            mBuilderCompatO
                    .setSmallIcon(R.drawable.ic_stat_camera_sync)
                    .setContentIntent(PendingIntent.getActivity(mContext,0,intent,0))
                    .setAutoCancel(true).setTicker(contentText)
                    .setContentTitle(message).setContentText(contentText)
                    .setOngoing(false);

            mNotificationManager.notify(Constants.NOTIFICATION_STORAGE_OVERQUOTA,mBuilderCompatO.build());
        } else {
            mBuilder
                    .setSmallIcon(R.drawable.ic_stat_camera_sync)
                    .setContentIntent(PendingIntent.getActivity(mContext,0,intent,0))
                    .setAutoCancel(true).setTicker(contentText)
                    .setContentTitle(message).setContentText(contentText)
                    .setOngoing(false);

            mNotificationManager.notify(Constants.NOTIFICATION_STORAGE_OVERQUOTA,mBuilder.build());
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        log("onStopJob");
        if (megaApi != null) {
            megaApi.cancelTransfers(MegaTransfer.TYPE_UPLOAD,this);
        }
        
        if(mVideoCompressor != null){
            mVideoCompressor.stop();
        }
        finish();
        return false;
    }

    public static void log(String message) {
        Util.log("CameraUploadsService",message);
    }

    @Override
    public void onRequestStart(MegaChatApiJava api,MegaChatRequest request) {

    }

    @Override
    public void onRequestUpdate(MegaChatApiJava api,MegaChatRequest request) {

    }

    @Override
    public void onRequestFinish(MegaChatApiJava api,MegaChatRequest request,MegaChatError e) {

    }

    @Override
    public void onRequestTemporaryError(MegaChatApiJava api,MegaChatRequest request,MegaChatError e) {

    }

    @Override
    public void onRequestStart(MegaApiJava api,MegaRequest request) {
        log("onRequestStart: " + request.getRequestString());
    }

    @Override
    public void onRequestUpdate(MegaApiJava api,MegaRequest request) {
        log("onRequestUpdate: " + request.getRequestString());
    }

    @Override
    public void onRequestFinish(MegaApiJava api,MegaRequest request,MegaError e) {
        log("onRequestFinish: " + request.getRequestString());
        requestFinished(api,request,e);

    }

    private synchronized void requestFinished(MegaApiJava api,MegaRequest request,MegaError e) {

        if (request.getType() == MegaRequest.TYPE_CREATE_FOLDER) {
            if (e.getErrorCode() == MegaError.API_OK) {
                log("Folder created: " + request.getName());
                String name = request.getName();
                if (name.contains(CAMERA_UPLOADS)) {
                    log("CamSync Folder UPDATED DB");
                    dbH.setCamSyncHandle(request.getNodeHandle());
                } else {
                    //Update in database
                    log("Secondary Folder UPDATED DB");
                    dbH.setSecondaryFolderHandle(request.getNodeHandle());
                }
            }
        } else if (request.getType() == MegaRequest.TYPE_COPY) {
            String nodeName = megaApi.getNodeByHandle(request.getNodeHandle()).getName();
            //TODO get new fingerprint
//            String oriFingerprint = megaApi.getNodeByHandle(request.getNodeHandle()).getFingerprint();
//            String newFingerprint = megaApi.getNodeByHandle(request.getNodeHandle()).getFingerprint();
            if (e.getErrorCode() == MegaError.API_OK) {
//                dbH.deleteSyncRecordByFingerprint(oriFingerprint,newFingerprint);
                dbH.deleteSyncRecordByFileName(nodeName);
            }
            Log.d("Yuan","updateProgressNotification 1" + request.getType() + " " + e.getErrorString());
            updateUpload();
        } else if (request.getType() == MegaRequest.TYPE_RENAME) {
            //No need to handle anything
        }
    }

    @Override
    public void onRequestTemporaryError(MegaApiJava api,MegaRequest request,MegaError e) {
        log("onRequestTemporaryError: " + request.getRequestString());
    }

    @Override
    public void onTransferStart(MegaApiJava api,MegaTransfer transfer) {
        log("onTransferStart: " + transfer.getFileName());
    }

    @Override
    public void onTransferUpdate(MegaApiJava api,MegaTransfer transfer) {
        transferUpdated(api,transfer);
    }

    private synchronized void transferUpdated(MegaApiJava api,MegaTransfer transfer) {
        if (canceled) {
            log("Transfer cancel: " + transfer.getFileName());

            if ((lock != null) && (lock.isHeld()))
                try {
                    lock.release();
                } catch (Exception ex) {
                }
            if ((wl != null) && (wl.isHeld()))
                try {
                    wl.release();
                } catch (Exception ex) {
                }

            megaApi.cancelTransfer(transfer);
            cancel();
            return;
        }

        if (isOverquota) {
            return;
        }

        updateProgressNotification();
    }

    @Override
    public void onTransferTemporaryError(MegaApiJava api,MegaTransfer transfer,MegaError e) {
        log("onTransferTemporaryError: " + transfer.getFileName());
    }

    @Override
    public void onTransferFinish(MegaApiJava api,MegaTransfer transfer,MegaError e) {
        log("Image sync finished: " + transfer.getFileName() + " size " + transfer.getTransferredBytes());
        log("transfer.getPath:" + transfer.getPath());
        log("transfer.getNodeHandle:" + transfer.getNodeHandle());

        transferFinished(api,transfer,e);
    }

    private synchronized void transferFinished(MegaApiJava api,MegaTransfer transfer,MegaError e) {
        if (canceled) {
            log("Image sync cancelled: " + transfer.getFileName());
            if ((lock != null) && (lock.isHeld())) {
                try {
                    lock.release();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            if ((wl != null) && (wl.isHeld())) {
                try {
                    wl.release();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            cancel();
        } else {
            String path = transfer.getPath();
            //todo need to test over quota
            if (isOverquota) {
                return;
            }

            if (e.getErrorCode() == MegaError.API_OK) {
                log("Image Sync OK: " + transfer.getFileName());
                log("IMAGESYNCFILE: " + path);

                dbH.deleteSyncRecordByPath(path);

                if (Util.isVideoFile(path)) {
                    log("Is video!!!");
                    File previewDir = PreviewUtils.getPreviewFolder(this);
                    File preview = new File(previewDir,MegaApiAndroid.handleToBase64(transfer.getNodeHandle()) + ".jpg");
                    File thumbDir = ThumbnailUtils.getThumbFolder(this);
                    File thumb = new File(thumbDir,MegaApiAndroid.handleToBase64(transfer.getNodeHandle()) + ".jpg");
                    megaApi.createThumbnail(path,thumb.getAbsolutePath());
                    megaApi.createPreview(path,preview.getAbsolutePath());

                    MegaNode node = megaApi.getNodeByHandle(transfer.getNodeHandle());
                    if (node != null) {
                        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                        retriever.setDataSource(path);

                        String location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
                        if (location != null) {
                            log("Location: " + location);

                            boolean secondTry = false;
                            try {
                                final int mid = location.length() / 2; //get the middle of the String
                                String[] parts = {location.substring(0,mid),location.substring(mid)};

                                Double lat = Double.parseDouble(parts[0]);
                                Double lon = Double.parseDouble(parts[1]);
                                log("Lat: " + lat); //first part
                                log("Long: " + lon); //second part

                                megaApi.setNodeCoordinates(node,lat,lon,null);
                            } catch (Exception exc) {
                                secondTry = true;
                                log("Exception, second try to set GPS coordinates");
                            }

                            if (secondTry) {
                                try {
                                    String latString = location.substring(0,7);
                                    String lonString = location.substring(8,17);

                                    Double lat = Double.parseDouble(latString);
                                    Double lon = Double.parseDouble(lonString);
                                    log("Lat2: " + lat); //first part
                                    log("Long2: " + lon); //second part

                                    megaApi.setNodeCoordinates(node,lat,lon,null);
                                } catch (Exception ex) {
                                    log("Exception again, no chance to set coordinates of video");
                                }
                            }
                        } else {
                            log("No location info");
                        }
                    }
                } else if (MimeTypeList.typeForName(path).isImage()) {
                    log("Is image!!!");

                    File previewDir = PreviewUtils.getPreviewFolder(this);
                    File preview = new File(previewDir,MegaApiAndroid.handleToBase64(transfer.getNodeHandle()) + ".jpg");
                    File thumbDir = ThumbnailUtils.getThumbFolder(this);
                    File thumb = new File(thumbDir,MegaApiAndroid.handleToBase64(transfer.getNodeHandle()) + ".jpg");
                    megaApi.createThumbnail(path,thumb.getAbsolutePath());
                    megaApi.createPreview(path,preview.getAbsolutePath());

                    MegaNode node = megaApi.getNodeByHandle(transfer.getNodeHandle());
                    if (node != null) {
                        try {
                            final ExifInterface exifInterface = new ExifInterface(path);
                            float[] latLong = new float[2];
                            if (exifInterface.getLatLong(latLong)) {
                                log("Latitude: " + latLong[0] + " Longitude: " + latLong[1]);
                                megaApi.setNodeCoordinates(node,latLong[0],latLong[1],null);
                            }

                        } catch (Exception exception) {
                            log("Couldn't read exif info: " + path);
                        }
                    }
                } else {
                    log("NOT video or image!");
                }
            } else if (e.getErrorCode() == MegaError.API_EOVERQUOTA) {
                log("OVERQUOTA ERROR: " + e.getErrorCode());
                isOverquota = true;
                cancel();
            } else {
                log("Image Sync FAIL: " + transfer.getFileName() + "___" + e.getErrorString());
                deleteFromDBIfFileNotExist(path);
            }

            //delete database record
            dbH.deleteSyncRecordByPath(path);
            //delete temp files
            if(path.startsWith(tempRoot)) {
                File file = new File(path);
                if (file.exists()) {
                    file.delete();
                }
            }
        }
        updateUpload();
    }

    private void updateUpload() {
        if (!canceled) {
            updateProgressNotification();
        }

        totalUploaded++;
        if (totalToUpload == totalUploaded) {
            if (isCompressedVideoPending() && !canceled) {
                startVideoCompression();
            } else {
                onQueueComplete();
            }
        }
    }

    @Override
    public boolean onTransferData(MegaApiJava api,MegaTransfer transfer,byte[] buffer) {
        return true;
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("NewApi")
    private synchronized void updateProgressNotification() {
        int pendingTransfers = megaApi.getNumPendingUploads();
        int totalTransfers = megaApi.getTotalUploads();
        long totalSizePendingTransfer = megaApi.getTotalUploadBytes();
        long totalSizeTransferred = megaApi.getTotalUploadedBytes();

        int progressPercent = (int)Math.round((double)totalSizeTransferred / totalSizePendingTransfer * 100);
        log("updateProgressNotification: " + progressPercent);

        String message;
        if (totalTransfers == 0) {
            message = getString(R.string.download_preparing_files);
        } else {
            int inProgress;
            if (pendingTransfers == 0) {
                inProgress = totalTransfers - pendingTransfers;
            } else {
                inProgress = totalTransfers - pendingTransfers + 1;
            }
            message = getResources().getQuantityString(R.plurals.upload_service_notification,totalTransfers,inProgress,totalTransfers);
        }

        String info = Util.getProgressSize(this,totalSizeTransferred,totalSizePendingTransfer);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,0,mIntent,0);
        showProgressNotification(progressPercent,pendingIntent,message,info,getString(R.string.settings_camera_notif_title));
    }

    private void updateTimeStamp() {
        //primary
        Long timeStampPrimary = dbH.findMaxTimestamp(false);
        if (timeStampPrimary == null) {
            timeStampPrimary = 0L;
        }
        if (timeStampPrimary > currentTimeStamp) {
            updateCurrentTimeStamp(timeStampPrimary);
        }

        //secondary
        if (secondaryEnabled) {
            Long timeStampSecondary = dbH.findMaxTimestamp(true);
            if (timeStampSecondary == null) {
                timeStampSecondary = 0L;
            }
            if (timeStampSecondary > secondaryTimeStamp) {
                updateSecondaryTimeStamp(timeStampSecondary);
            }
        }
    }

    private void updateCurrentTimeStamp(long timeStamp) {
        currentTimeStamp = timeStamp;
        dbH.setCamSyncTimeStamp(currentTimeStamp);
    }

    private void updateSecondaryTimeStamp(long timeStamp) {
        secondaryTimeStamp = timeStamp;
        dbH.setSecSyncTimeStamp(secondaryTimeStamp);
    }

    private void deleteFromDBIfFileNotExist(String path) {
        File f = new File(path);
        if (!f.exists()) {
            dbH.deleteSyncRecordByPath(path);
        }
    }

    private boolean isCompressedVideoPending() {
        return dbH.findVideoSyncRecordsByState(STATUS_TO_COMPRESS).size() > 0 && String.valueOf(VIDEO_QUALITY_MEDIUM).equals(prefs.getUploadVideoQuality());
    }

    private void startVideoCompression() {
        Log.d("Yuan","startVideoCompression");

        List<SyncRecord> fullList = dbH.findVideoSyncRecordsByState(STATUS_TO_COMPRESS);
        megaApi.resetTotalUploads();
        totalUploaded = 0;
        totalToUpload = 0;
    
        mVideoCompressor = new VideoCompressor(getApplicationContext(),CameraUploadsService.this);
        mVideoCompressor.setPendingList(fullList);
        mVideoCompressor.setOutputRoot(tempRoot);
        long totalPendingSizeInMB = mVideoCompressor.getTotalInputSize() / (1024 * 1024);
        Log.d("Yuan",totalPendingSizeInMB + "byte to Conversion");

        double availableFreeSpace = Double.MAX_VALUE;
        try {
            StatFs stat = new StatFs(tempRoot);
            availableFreeSpace = (double)stat.getAvailableBlocks() * (double)stat.getBlockSize();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        if (availableFreeSpace < mVideoCompressor.getTotalInputSize()) {
            stopForeground(true);
            Intent intent = new Intent(this,ManagerActivityLollipop.class);
            intent.setAction(Constants.ACTION_SHOW_SETTINGS);
            PendingIntent pendingIntent = PendingIntent.getActivity(this,0,intent,0);
            showNotification("No Space","Not enough space to perform compression.",pendingIntent);
            return;
        }

        if (shouldStartVideoCompression(totalPendingSizeInMB)) {
            showNotification("Start compression","Starting",mPendingIntent);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    mVideoCompressor.start();
                }
            });
            t.start();
        }else{
            stopForeground(true);
            Intent intent = new Intent(this,ManagerActivityLollipop.class);
            intent.setAction(Constants.ACTION_SHOW_SETTINGS);
            PendingIntent pendingIntent = PendingIntent.getActivity(this,0,intent,0);
            showNotification("Over limit","Do you want to charge or change setting",pendingIntent);
        }

    }

    private boolean shouldStartVideoCompression(long queueSize) {

        if(prefs.getConversionOnCharging() != null && Boolean.parseBoolean(prefs.getConversionOnCharging())){
            int queueSizeLimit = Integer.parseInt(prefs.getChargingOnSize());
            if (queueSize > queueSizeLimit && !Util.isCharging(mContext)) {
                return false;
            }
            return true;
        } else {
            return true;
        }
    }

    public synchronized void onCompressUpdateProgress(int progress,String currentIndexString) {
        String message = progress + "% compression has been completed";
        showProgressNotification(progress,mPendingIntent,message,currentIndexString,"");
    }

    public synchronized void onCompressSuccessful(String originalPath) {
        Log.d("Yuan","compression successfully " + originalPath);
        dbH.updateSyncRecordStatusByLocalPath(STATUS_PENDING,originalPath);
    }

    public synchronized void onCompressFailed(String originalPath) {
        Log.d("Yuan","compression failed " + originalPath);
        //file can not be compress will be uploaded directly?
        dbH.updateSyncRecordStatusByLocalPath(STATUS_PENDING,originalPath);
    }

    public void onCompressFinished(String currentIndexString) {
        if(!canceled){
            ArrayList<SyncRecord> compressedList = new ArrayList<>(dbH.findVideoSyncRecordsByState(STATUS_PENDING));
            startParallelUpload(compressedList,true);
        }
    }

    private void showNotification(String title,String content,PendingIntent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(notificationChannelId,notificationChannelName,NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(false);
            channel.setSound(null,null);
            mNotificationManager.createNotificationChannel(channel);
        }

        mBuilder = new NotificationCompat.Builder(mContext,notificationChannelId);
        mBuilder.setSmallIcon(R.drawable.ic_stat_camera_sync)
                .setContentIntent(intent)
                .setOngoing(false)
                .setContentTitle(title)
                .setContentText(content)
                .setOnlyAlertOnce(true);
        mNotification = mBuilder.build();

        mNotificationManager.notify(notificationId,mNotification);
    }

    private void showProgressNotification(int progressPercent,PendingIntent pendingIntent,String message,String subText,String contentText) {
        mNotification = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(notificationChannelId,notificationChannelName,NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(true);
            channel.setSound(null,null);
            mNotificationManager.createNotificationChannel(channel);

            NotificationCompat.Builder mBuilderCompat = new NotificationCompat.Builder(mContext,notificationChannelId);

            mBuilderCompat
                    .setSmallIcon(R.drawable.ic_stat_camera_sync)
                    .setProgress(100,progressPercent,false)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setContentTitle(message)
                    .setSubText(subText)
                    .setContentText(contentText)
                    .setOnlyAlertOnce(true);

            mNotification = mBuilderCompat.build();
        } else if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            mBuilder.setSmallIcon(R.drawable.ic_stat_camera_sync)
                    .setProgress(100,progressPercent,false)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setContentTitle(message)
                    .setSubText(subText)
                    .setContentText(contentText)
                    .setOnlyAlertOnce(true);
            mNotification = mBuilder.getNotification();
        }

        mNotificationManager.notify(notificationId,mNotification);
    }

    private void removeGPSCoordinates(String filePath) {
        try {
            ExifInterface exif = new ExifInterface(filePath);
            float output[] = new float[2];
            exif.getLatLong(output);
            
            //todo save data to DB
            //dbH.setGPSByNewPath(output[1], output[2]);
            
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE,"0/1,0/1,0/1000");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF,"0");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE,"0/1,0/1,0/1000");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"0");
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE,"0/1,0/1,0/1000");
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF,"0");
            exif.saveAttributes();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String createTempFile(SyncRecord file) {
        File srcFile = new File(file.getLocalPath());
        if(!srcFile.exists()){
            return ERROR_SOURCE_FILE_NOT_EXIST;
        }
        
        try {
            StatFs stat = new StatFs(tempRoot);
            double availableFreeSpace = (double)stat.getAvailableBlocks() * (double)stat.getBlockSize();
            if (availableFreeSpace <= srcFile.length()) {
                return ERROR_NOT_ENOUGH_SPACE;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        String destPath = file.getNewPath();
        File destFile = new File(destPath);
        try {
            Util.copyFile(srcFile,destFile);
            removeGPSCoordinates(destPath);
        } catch (IOException e) {
            e.printStackTrace();
            return ERROR_CREATE_FILE_IO_ERROR;
        }
        return destPath;
    }
}