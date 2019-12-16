import { NativeModules, NativeEventEmitter } from 'react-native';
import RestartManager from "./RestartManager";
import log from "./logging";


const NativeHotUpdate = NativeModules.HotUpdate;

let serverUrl = 'http://10.221.230.151:3000';
/**
 * updateMap.json {
 *  1.5.1: [
 * 		{ appVersion: "1.5.1", patchCode: 1, label: "v1", packageHash: "xxxx", baseUrl: "http://aliyunOss.com/1.5.1/patch1" },
 * 		{ appVersion: "1.5.1", patchCode: 2, label: "v2", packageHash: "xxxx", baseUrl: "http://aliyunOss.com/1.5.1/patch2" },
 * 		...
 * 	],
 *  1.5.2: [
 * 		{ appVersion: "1.5.2", patchCode: 1, label: "v1", packageHash: "xxxx", baseUrl: "http://aliyunOss.com/1.5.2/patch1" },
 * 		{ appVersion: "1.5.2", patchCode: 2, label: "v2", packageHash: "xxxx", baseUrl: "http://aliyunOss.com/1.5.2/patch2" },
 * 		...
 * 	],
 * 	...
 * }
 * 
 * 
 * updateRecord.json [
 * 		{ label: v1, target: "1.5.1", description: "xxxxxxxxxxx", mandatory: false },
 * 		{ label: v2, target: "1.5.1", description: "xxxxxxxxxxx", mandatory: false },
 * 		{ label: v3, target: "1.5.1 - 1.5.2", description: "xxxxxxxxxxx", mandatory: false },
 * 		{ label: v4, target: "1.5.1", description: "xxxxxxxxxxx", mandatory: false },
 * ]
 */

function setServerUrl(url) {
	if (!url) return
	serverUrl = url;
}


function get(url) {
	return fetch(url).then((response) => response.json())
}

async function getUpdateMap() {
	return await get(`${serverUrl}/updateMap.json`)
}

async function getUpdateRecord() {
	return await get(`${serverUrl}/updateRecord.json`)
}

/**
 * remotePackage: {
 * 	packageHash: string,
 * 	description: string,
 * 	downloadUrl: string,
 * 	mandatory: bool,
 * }
 */
async function checkUpdate() {
	let updateMap = await getUpdateMap()
	let updateRecord = await getUpdateRecord()
	let currentVersionInfo = await NativeHotUpdate.getCurrentVersionInfo()
	let { appVersion, appPatch, appHash } = currentVersionInfo
	let remotePackage = null

	if (updateMap[appVersion]) {
		let patchList = updateMap[appVersion] // 补丁列表
		let latestPatch = patchList[patchList.length - 1] // 最新补丁

		// 判断是否需要更新
		if (!appPatch) { 
			// 第一次热更，全量更新
			remotePackage = {
				appVersion: latestPatch.appVersion,
				patchCode: latestPatch.patchCode,
				packageHash: latestPatch.packageHash,
				downloadUrl: `${latestPatch.baseUrl}/patch${latestPatch.patchCode}.zip`
			}
		} else {
			// 否则，增量更新
			if (latestPatch.patchCode > appPatch) {
				remotePackage = {
					appVersion: latestPatch.appVersion,
					patchCode: latestPatch.patchCode,
					packageHash: latestPatch.packageHash,
					downloadUrl: `${latestPatch.baseUrl}/patch${appPatch}-patch${latestPatch.patchCode}.zip`
				}
			}
		}

		// 如需更新，获取更新描述
		if (remotePackage) {
			reverseIterate(updateRecord, (record) => {
				if (record.label === latestPatch.label) {
					remotePackage.description =record.description
					remotePackage.mandatory =record.mandatory
					return true
				}
			})
		}
	}

	return remotePackage
}

// 反向遍历
function reverseIterate(list, callback) {
	let len = list.length
	let shouldBreak = false
	for (let i = len - 1; i >= 0; i--) {
		if (callback) {
			shouldBreak = callback(list[i])
			if (shouldBreak) {
				break
			}
		}
	}
}

async function download(remotePackage, downloadProgressCallback) {
	if (!remotePackage) return log('Cannot download an update without remotePackage');
	if (!remotePackage.downloadUrl) return log('Cannot download an update without a download url');

	let downloadProgressSubscription;
	if (downloadProgressCallback) {
		const codePushEventEmitter = new NativeEventEmitter(NativeHotUpdate);
		downloadProgressSubscription = codePushEventEmitter.addListener(
			"HotUpdateDownloadProgress",
			downloadProgressCallback
		);	
	}

	try {
		let downloadResult = await NativeHotUpdate.downloadUpdate(remotePackage, !!downloadProgressCallback)
	} finally {
		downloadProgressSubscription && downloadProgressSubscription.remove();
	}
}

async function install(updatePackage, installMode = 0, minimumBackgroundDuration = 0) {
	await NativeHotUpdate.installUpdate(updatePackage, installMode, minimumBackgroundDuration)
	if (installMode == NativeHotUpdate.hotUpdateInstallModeImmediate) {
		RestartManager.restartApp(false);
	} else {
		RestartManager.clearPendingRestart();
	}
}

// This ensures that notifyApplicationReadyInternal is only called once
// in the lifetime of this module instance.
const notifyApplicationReady = (() => {
  let notifyApplicationReadyPromise;
  return () => {
    if (!notifyApplicationReadyPromise) {
      notifyApplicationReadyPromise = notifyApplicationReadyInternal();
    }

    return notifyApplicationReadyPromise;
  };
})();

async function notifyApplicationReadyInternal() {
  await NativeHotUpdate.notifyApplicationReady();
}

async function getStat() {
	return NativeHotUpdate.getStat()
}


let HotUpdate

if (NativeHotUpdate) {
	HotUpdate = {
		download,
		checkUpdate,
		install,
		notifyAppReady: notifyApplicationReady,
		getStat,
		InstallMode: {
      IMMEDIATE: NativeHotUpdate.hotUpdateInstallModeImmediate, // Restart the app immediately
      ON_NEXT_RESTART: NativeHotUpdate.hotUpdateInstallModeOnNextRestart, // Don't artificially restart the app. Allow the update to be "picked up" on the next app restart
      ON_NEXT_RESUME: NativeHotUpdate.hotUpdateInstallModeOnNextResume, // Restart the app the next time it is resumed from the background
      ON_NEXT_SUSPEND: NativeHotUpdate.hotUpdateInstallModeOnNextSuspend // Restart the app _while_ it is in the background,
      // but only after it has been in the background for "minimumBackgroundDuration" seconds (0 by default),
      // so that user context isn't lost unless the app suspension is long enough to not matter
    },
	}
} else {
	log("The HotUpdate module doesn't appear to be properly installed. Please double-check that everything is setup correctly.");
}

export default HotUpdate;
