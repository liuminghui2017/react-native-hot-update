#if __has_include(<React/RCTEventEmitter.h>)
#import <React/RCTEventEmitter.h>
#elif __has_include("RCTEventEmitter.h")
#import "RCTEventEmitter.h"
#else
#import "React/RCTEventEmitter.h"   // Required when used as a Pod in a Swift project
#endif

#import <Foundation/Foundation.h>

@interface HotUpdate : RCTEventEmitter

+ (NSURL *)binaryBundleURL;
/*
 * This method is used to retrieve the URL for the most recent
 * version of the JavaScript bundle. This could be either the
 * bundle that was packaged with the app binary, or the bundle
 * that was downloaded as part of a CodePush update. The value returned
 * should be used to "bootstrap" the React Native bridge.
 *
 * This method assumes that your JS bundle is named "main.jsbundle"
 * and therefore, if it isn't, you should use either the bundleURLForResource:
 * or bundleURLForResource:withExtension: methods to override that behavior.
 */
+ (NSURL *)bundleURL;

+ (NSURL *)bundleURLForResource:(NSString *)resourceName;

+ (NSURL *)bundleURLForResource:(NSString *)resourceName
                  withExtension:(NSString *)resourceExtension;

+ (NSURL *)bundleURLForResource:(NSString *)resourceName
                  withExtension:(NSString *)resourceExtension
                   subdirectory:(NSString *)resourceSubdirectory;

+ (NSURL *)bundleURLForResource:(NSString *)resourceName
                  withExtension:(NSString *)resourceExtension
                   subdirectory:(NSString *)resourceSubdirectory
                         bundle:(NSBundle *)resourceBundle;

+ (NSString *)getApplicationSupportDirectory;

+ (NSString *)bundleAssetsPath;

/*
 * This method allows the version of the app's binary interface
 * to be specified, which would otherwise default to the
 * App Store version of the app.
 */
+ (void)overrideAppVersion:(NSString *)appVersion;

/*
 * This method allows dynamically setting the app's
 * deployment key, in addition to setting it via
 * the Info.plist file's CodePushDeploymentKey setting.
 */
+ (void)setDeploymentKey:(NSString *)deploymentKey;

/*
 * This method checks to see whether a specific package hash
 * has previously failed installation.
 */
+ (BOOL)isFailedHash:(NSString*)packageHash;

/*
 * This method checks to see whether a specific package hash
 * represents a downloaded and installed update, that hasn't
 * been applied yet via an app restart.
 */
+ (BOOL)isPendingUpdate:(NSString*)packageHash;

// The below methods are only used during tests.
+ (BOOL)isUsingTestConfiguration;
+ (void)setUsingTestConfiguration:(BOOL)shouldUseTestConfiguration;
+ (void)clearUpdates;

@end

@interface HotUpdateConfig : NSObject

@property (copy) NSString *appVersion;
@property (readonly) NSString *buildVersion;
@property (readonly) NSDictionary *configuration;
@property (copy) NSString *deploymentKey;
@property (copy) NSString *serverURL;
@property (copy) NSString *publicKey;

+ (instancetype)current;

@end

@interface HotUpdateDownloadHandler : NSObject <NSURLConnectionDelegate>

@property (strong) NSOutputStream *outputFileStream;
@property long long expectedContentLength;
@property long long receivedContentLength;
@property dispatch_queue_t operationQueue;
@property (copy) void (^progressCallback)(long long, long long);
@property (copy) void (^doneCallback)(BOOL);
@property (copy) void (^failCallback)(NSError *err);
@property NSString *downloadUrl;

- (id)init:(NSString *)downloadFilePath
operationQueue:(dispatch_queue_t)operationQueue
progressCallback:(void (^)(long long, long long))progressCallback
doneCallback:(void (^)(BOOL))doneCallback
failCallback:(void (^)(NSError *err))failCallback;

- (void)download:(NSString*)url;

@end

@interface HotUpdateErrorUtils : NSObject

+ (NSError *)errorWithMessage:(NSString *)errorMessage;
+ (BOOL)isHotUpdateError:(NSError *)error;

@end

@interface HotUpdatePackage : NSObject

+ (void)downloadPackage:(NSDictionary *)updatePackage
 expectedBundleFileName:(NSString *)expectedBundleFileName
         operationQueue:(dispatch_queue_t)operationQueue
       progressCallback:(void (^)(long long, long long))progressCallback
           doneCallback:(void (^)())doneCallback
           failCallback:(void (^)(NSError *err))failCallback;

+ (NSDictionary *)getCurrentPackage:(NSError **)error;
+ (NSDictionary *)getPreviousPackage:(NSError **)error;
+ (NSString *)getCurrentPackageFolderPath:(NSError **)error;
+ (NSString *)getCurrentPackageBundlePath:(NSError **)error;
+ (NSString *)getCurrentPackageHash:(NSError **)error;
+ (NSMutableDictionary *)getCurrentPackageInfo:(NSError **)error;

+ (NSDictionary *)getPackage:(NSString *)packageHash
                       error:(NSError **)error;

+ (NSString *)getPackageFolderPath:(NSString *)packageHash;

+ (BOOL)installPackage:(NSDictionary *)updatePackage
   removePendingUpdate:(BOOL)removePendingUpdate
                 error:(NSError **)error;

+ (void)rollbackPackage;

// The below methods are only used during tests.
+ (void)clearUpdates;
+ (void)downloadAndReplaceCurrentBundle:(NSString *)remoteBundleUrl;

@end


@interface HotUpdateUpdateUtils : NSObject

+ (BOOL)copyEntriesInFolder:(NSString *)sourceFolder
                 destFolder:(NSString *)destFolder
                      error:(NSError **)error;

+ (NSString *)findMainBundleInFolder:(NSString *)folderPath
                    expectedFileName:(NSString *)expectedFileName
                               error:(NSError **)error;

+ (NSString *)assetsFolderName;
// + (NSString *)getHashForBinaryContents:(NSURL *)binaryBundleUrl
//                                  error:(NSError **)error;

// + (NSString *)manifestFolderPrefix;
+ (NSString *)modifiedDateStringOfFileAtURL:(NSURL *)fileURL;

// + (BOOL)isHashIgnoredFor:(NSString *) relativePath;

// + (BOOL)verifyFolderHash:(NSString *)finalUpdateFolder
//                    expectedHash:(NSString *)expectedHash
//                           error:(NSError **)error;


@end

void CPLog(NSString *formatString, ...);

typedef NS_ENUM(NSInteger, HotUpdateInstallMode) {
    HotUpdateInstallModeImmediate,
    HotUpdateInstallModeOnNextRestart,
    HotUpdateInstallModeOnNextResume,
    HotUpdateInstallModeOnNextSuspend
};

typedef NS_ENUM(NSInteger, HotUpdateUpdateState) {
    HotUpdateUpdateStateRunning,
    HotUpdateUpdateStatePending,
    HotUpdateUpdateStateLatest
};