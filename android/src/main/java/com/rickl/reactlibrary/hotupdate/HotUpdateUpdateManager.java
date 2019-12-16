
package com.rickl.reactlibrary.hotupdate;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;

public class HotUpdateUpdateManager {

    private String mDocumentsDirectory;

    public HotUpdateUpdateManager(String documentsDirectory) {
        mDocumentsDirectory = documentsDirectory;
    }

    // private String getDownloadFilePath() {
    //     return HotUpdateUtils.appendPathComponent(getHotUpdatePath(), HotUpdateConstants.DOWNLOAD_FILE_NAME);
    // }

    private String getUnzippedFolderPath() {
        return HotUpdateUtils.appendPathComponent(getHotUpdatePath(), HotUpdateConstants.UNZIPPED_FOLDER_NAME);
    }

    private String getDocumentsDirectory() {
        return mDocumentsDirectory;
    }

    /* 获取本插件的根目录 file://xxxxxx/HotUpdate */
    private String getHotUpdatePath() {
        String hotUpdatePath = HotUpdateUtils.appendPathComponent(getDocumentsDirectory(), HotUpdateConstants.HOT_UPDATE_FOLDER_PREFIX);
        return hotUpdatePath;
    }

    /**
     * 获取版本记录文件 file://xxxxxx/HotUpdate/hotupdate.json
     * hotupdate: {
     *   currentPackage: "xxx",
     *   previousPackage: "xxx"
     * }
     */
    private String getStatusFilePath() {
        return HotUpdateUtils.appendPathComponent(getHotUpdatePath(), HotUpdateConstants.STATUS_FILE);
    }

    /**
     * 将hotupdate.json文件内容转换成JSONObject
     */
    public JSONObject getCurrentPackageInfo() {
        String statusFilePath = getStatusFilePath();
        if (!FileUtils.fileAtPathExists(statusFilePath)) {
            return new JSONObject();
        }

        try {
            return HotUpdateUtils.getJsonObjectFromFile(statusFilePath);
        } catch (IOException e) {
            // Should not happen.
            throw new HotUpdateUnknownException("Error getting current package info", e);
        }
    }

    // 更新hotupdate.json文件
    public void updateCurrentPackageInfo(JSONObject packageInfo) {
        try {
            HotUpdateUtils.writeJsonToFile(packageInfo, getStatusFilePath());
        } catch (IOException e) {
            // Should not happen.
            throw new HotUpdateUnknownException("Error updating current package info", e);
        }
    }

    // 获取当前版本bundle所在目录 file://xxxx/HotUpdate/xxxhash
    public String getCurrentPackageFolderPath() {
        JSONObject info = getCurrentPackageInfo();
        String packageHash = info.optString(HotUpdateConstants.CURRENT_PACKAGE_KEY, null);
        if (packageHash == null) {
            return null;
        }

        return getPackageFolderPath(packageHash);
    }

    // 获取当前版本index.android.bundle文件路径
    public String getCurrentPackageBundlePath(String bundleFileName) {
        String packageFolder = getCurrentPackageFolderPath();
        if (packageFolder == null) {
            return null;
        }

        return HotUpdateUtils.appendPathComponent(packageFolder, bundleFileName);
    }

    // 根据版本hash生成版本目录
    public String getPackageFolderPath(String packageHash) {
        return HotUpdateUtils.appendPathComponent(getHotUpdatePath(), packageHash);
    }

    // 获取当前版本的hash
    public String getCurrentPackageHash() {
        JSONObject info = getCurrentPackageInfo();
        return info.optString(HotUpdateConstants.CURRENT_PACKAGE_KEY, null);
    }

    // 获取上一个版本的hash
    public String getPreviousPackageHash() {
        JSONObject info = getCurrentPackageInfo();
        return info.optString(HotUpdateConstants.PREVIOUS_PACKAGE_KEY, null);
    }

    // 获取当前版本的app.json
    public JSONObject getCurrentPackage() {
        String packageHash = getCurrentPackageHash();
        if (packageHash == null) {
            return null;
        }

        return getPackage(packageHash);
    }

    // 获取上一版本的app.json
    public JSONObject getPreviousPackage() {
        String packageHash = getPreviousPackageHash();
        if (packageHash == null) {
            return null;
        }

        return getPackage(packageHash);
    }

    // 根据版本hash，获取版本的描述文件app.json
    public JSONObject getPackage(String packageHash) {
        String folderPath = getPackageFolderPath(packageHash);
        String packageFilePath = HotUpdateUtils.appendPathComponent(folderPath, HotUpdateConstants.PACKAGE_FILE_NAME);
        try {
            return HotUpdateUtils.getJsonObjectFromFile(packageFilePath);
        } catch (IOException e) {
            return null;
        }
    }

    // 下载更新包
    public void downloadPackage(JSONObject updatePackage, String expectedBundleFileName,
                                DownloadProgressCallback progressCallback) throws IOException {
        String newUpdateHash = updatePackage.optString(HotUpdateConstants.PACKAGE_HASH_KEY, null);
        String newUpdateFolderPath = getPackageFolderPath(newUpdateHash);
        String newUpdateMetadataPath = HotUpdateUtils.appendPathComponent(newUpdateFolderPath, HotUpdateConstants.PACKAGE_FILE_NAME);
        if (FileUtils.fileAtPathExists(newUpdateFolderPath)) {
            // This removes any stale data in newPackageFolderPath that could have been left
            // uncleared due to a crash or error during the download or install process.
            FileUtils.deleteDirectoryAtPath(newUpdateFolderPath);
        }

        String downloadUrlString = updatePackage.optString(HotUpdateConstants.DOWNLOAD_URL_KEY, null);
        HttpURLConnection connection = null;
        BufferedInputStream bin = null;
        FileOutputStream fos = null;
        BufferedOutputStream bout = null;
        File downloadFile = null;
        boolean isZip = false;

        // Download the file while checking if it is a zip and notifying client of progress.
        try {
            URL downloadUrl = new URL(downloadUrlString);
            connection = (HttpURLConnection) (downloadUrl.openConnection());
            connection.setRequestProperty("Accept-Encoding", "identity");
            bin = new BufferedInputStream(connection.getInputStream());

            long totalBytes = connection.getContentLength();
            long receivedBytes = 0;

            File downloadFolder = new File(getHotUpdatePath());
            downloadFolder.mkdirs();
            downloadFile = new File(downloadFolder, HotUpdateConstants.DOWNLOAD_FILE_NAME);
            fos = new FileOutputStream(downloadFile);
            bout = new BufferedOutputStream(fos, HotUpdateConstants.DOWNLOAD_BUFFER_SIZE);
            byte[] data = new byte[HotUpdateConstants.DOWNLOAD_BUFFER_SIZE];
            byte[] header = new byte[4];

            int numBytesRead = 0;
            while ((numBytesRead = bin.read(data, 0, HotUpdateConstants.DOWNLOAD_BUFFER_SIZE)) >= 0) {
                if (receivedBytes < 4) {
                    for (int i = 0; i < numBytesRead; i++) {
                        int headerOffset = (int) (receivedBytes) + i;
                        if (headerOffset >= 4) {
                            break;
                        }

                        header[headerOffset] = data[i];
                    }
                }

                receivedBytes += numBytesRead;
                bout.write(data, 0, numBytesRead);
                progressCallback.call(new DownloadProgress(totalBytes, receivedBytes));
            }

            if (totalBytes != receivedBytes) {
                throw new HotUpdateUnknownException("Received " + receivedBytes + " bytes, expected " + totalBytes);
            }

            isZip = ByteBuffer.wrap(header).getInt() == 0x504b0304;
        } catch (MalformedURLException e) {
            throw new HotUpdateMalformedDataException(downloadUrlString, e);
        } finally {
            try {
                if (bout != null) bout.close();
                if (fos != null) fos.close();
                if (bin != null) bin.close();
                if (connection != null) connection.disconnect();
            } catch (IOException e) {
                throw new HotUpdateUnknownException("Error closing IO resources.", e);
            }
        }

        // 下载完成
        if (isZip) {
            // Unzip the downloaded file and then delete the zip
            String unzippedFolderPath = getUnzippedFolderPath();
            FileUtils.unzipFile(downloadFile, unzippedFolderPath);
            FileUtils.deleteFileOrFolderSilently(downloadFile);

            // Merge contents with current update based on the manifest
            String diffManifestFilePath = HotUpdateUtils.appendPathComponent(unzippedFolderPath,
                    HotUpdateConstants.DIFF_MANIFEST_FILE_NAME);
            boolean isDiffUpdate = FileUtils.fileAtPathExists(diffManifestFilePath);
            if (isDiffUpdate) {
                String currentPackageFolderPath = getCurrentPackageFolderPath();
                HotUpdateUpdateUtils.copyNecessaryFilesFromCurrentPackage(diffManifestFilePath, currentPackageFolderPath, newUpdateFolderPath);
                File diffManifestFile = new File(diffManifestFilePath);
                diffManifestFile.delete();
            }

            FileUtils.copyDirectoryContents(unzippedFolderPath, newUpdateFolderPath);
            FileUtils.deleteFileAtPathSilently(unzippedFolderPath);

        } else {
            // File is a jsbundle, move it to a folder with the packageHash as its name
            FileUtils.moveFile(downloadFile, newUpdateFolderPath, expectedBundleFileName);
        }

        // Save metadata to the folder. 将更新信息写入到app.json文件
        HotUpdateUtils.writeJsonToFile(updatePackage, newUpdateMetadataPath);
    }

    // 安装更新包 (更新hotupdate.json里对应的current及previous的版本索引)
    public void installPackage(JSONObject updatePackage, boolean removePendingUpdate) {
        String packageHash = updatePackage.optString(HotUpdateConstants.PACKAGE_HASH_KEY, null);
        JSONObject info = getCurrentPackageInfo();

        String currentPackageHash = info.optString(HotUpdateConstants.CURRENT_PACKAGE_KEY, null);
        if (packageHash != null && packageHash.equals(currentPackageHash)) {
            // The current package is already the one being installed, so we should no-op.
            HotUpdateUtils.log("===> The current package is already the one being installed, so we should no-op.");
            return;
        }

        if (removePendingUpdate) {
            String currentPackageFolderPath = getCurrentPackageFolderPath();
            if (currentPackageFolderPath != null) {
                FileUtils.deleteDirectoryAtPath(currentPackageFolderPath);
            }
        } else {
            String previousPackageHash = getPreviousPackageHash();
            if (previousPackageHash != null && !previousPackageHash.equals(packageHash)) {
                FileUtils.deleteDirectoryAtPath(getPackageFolderPath(previousPackageHash));
            }

            HotUpdateUtils.setJSONValueForKey(info, HotUpdateConstants.PREVIOUS_PACKAGE_KEY, info.optString(HotUpdateConstants.CURRENT_PACKAGE_KEY, null));
        }

        HotUpdateUtils.setJSONValueForKey(info, HotUpdateConstants.CURRENT_PACKAGE_KEY, packageHash);
        updateCurrentPackageInfo(info);
    }

    public void rollbackPackage() {
        JSONObject info = getCurrentPackageInfo();
        String currentPackageFolderPath = getCurrentPackageFolderPath();
        FileUtils.deleteDirectoryAtPath(currentPackageFolderPath);
        HotUpdateUtils.setJSONValueForKey(info, HotUpdateConstants.CURRENT_PACKAGE_KEY, info.optString(HotUpdateConstants.PREVIOUS_PACKAGE_KEY, null));
        HotUpdateUtils.setJSONValueForKey(info, HotUpdateConstants.PREVIOUS_PACKAGE_KEY, null);
        updateCurrentPackageInfo(info);
    }

    // public void downloadAndReplaceCurrentBundle(String remoteBundleUrl, String bundleFileName) throws IOException {
    //     URL downloadUrl;
    //     HttpURLConnection connection = null;
    //     BufferedInputStream bin = null;
    //     FileOutputStream fos = null;
    //     BufferedOutputStream bout = null;
    //     try {
    //         downloadUrl = new URL(remoteBundleUrl);
    //         connection = (HttpURLConnection) (downloadUrl.openConnection());
    //         bin = new BufferedInputStream(connection.getInputStream());
    //         File downloadFile = new File(getCurrentPackageBundlePath(bundleFileName));
    //         downloadFile.delete();
    //         fos = new FileOutputStream(downloadFile);
    //         bout = new BufferedOutputStream(fos, HotUpdateConstants.DOWNLOAD_BUFFER_SIZE);
    //         byte[] data = new byte[HotUpdateConstants.DOWNLOAD_BUFFER_SIZE];
    //         int numBytesRead = 0;
    //         while ((numBytesRead = bin.read(data, 0, HotUpdateConstants.DOWNLOAD_BUFFER_SIZE)) >= 0) {
    //             bout.write(data, 0, numBytesRead);
    //         }
    //     } catch (MalformedURLException e) {
    //         throw new CodePushMalformedDataException(remoteBundleUrl, e);
    //     } finally {
    //         try {
    //             if (bout != null) bout.close();
    //             if (fos != null) fos.close();
    //             if (bin != null) bin.close();
    //             if (connection != null) connection.disconnect();
    //         } catch (IOException e) {
    //             throw new CodePushUnknownException("Error closing IO resources.", e);
    //         }
    //     }
    // }

    public void clearUpdates() {
        FileUtils.deleteDirectoryAtPath(getHotUpdatePath());
    }
}
