#if __has_include(<React/RCTAssert.h>)
#import <React/RCTAssert.h>
#import <React/RCTBridgeModule.h>
#import <React/RCTConvert.h>
#import <React/RCTEventDispatcher.h>
#import <React/RCTRootView.h>
#import <React/RCTUtils.h>
#else // back compatibility for RN version < 0.40
#import "RCTAssert.h"
#import "RCTBridgeModule.h"
#import "RCTConvert.h"
#import "RCTEventDispatcher.h"
#import "RCTRootView.h"
#import "RCTUtils.h"
#endif

#import "HotUpdate.h"

@interface HotUpdate () <RCTBridgeModule, RCTFrameUpdateObserver>
@end

@implementation HotUpdate {
    BOOL _hasResumeListener;
    BOOL _isFirstRunAfterUpdate;
    int _minimumBackgroundDuration;
    NSDate *_lastResignedDate;
    HotUpdateInstallMode _installMode;
    NSTimer *_appSuspendTimer;

    // Used to coordinate the dispatching of download progress events to JS.
    long long _latestExpectedContentLength;
    long long _latestReceivedConentLength;
    BOOL _didUpdateProgress;
}

RCT_EXPORT_MODULE()

#pragma mark - Private constants

// These constants represent emitted events
static NSString *const DownloadProgressEvent = @"HotUpdateDownloadProgress";

// These constants represent valid deployment statuses
static NSString *const DeploymentFailed = @"DeploymentFailed";
static NSString *const DeploymentSucceeded = @"DeploymentSucceeded";

// These keys represent the names we use to store data in NSUserDefaults
static NSString *const FailedUpdatesKey = @"HOT_UPDATE_FAILED_UPDATES";
static NSString *const PendingUpdateKey = @"HOT_UPDATE_PENDING_UPDATE";

// These keys are already "namespaced" by the PendingUpdateKey, so
// their values don't need to be obfuscated to prevent collision with app data
static NSString *const PendingUpdateHashKey = @"hash";
static NSString *const PendingUpdateIsLoadingKey = @"isLoading";

// These keys are used to inspect/augment the metadata
// that is associated with an update's package.
static NSString *const AppVersionKey = @"appVersion";
static NSString *const AppPatchCode = @"patchCode";
static NSString *const BinaryBundleDateKey = @"binaryDate";
static NSString *const PackageHashKey = @"packageHash";
static NSString *const PackageIsPendingKey = @"isPending";

#pragma mark - Static variables

static BOOL isRunningBinaryVersion = NO;
static BOOL needToReportRollback = NO;
static BOOL testConfigurationFlag = NO;

// These values are used to save the NS bundle, name, extension and subdirectory
// for the JS bundle in the binary.
static NSBundle *bundleResourceBundle = nil;
static NSString *bundleResourceExtension = @"jsbundle";
static NSString *bundleResourceName = @"main";
static NSString *bundleResourceSubdirectory = nil;

+ (void)initialize
{
    [super initialize];
    if (self == [HotUpdate class]) {
        // Use the mainBundle by default.
        bundleResourceBundle = [NSBundle mainBundle];
    }
}

#pragma mark - Public Obj-C API

+ (NSURL *)binaryBundleURL
{
    return [bundleResourceBundle URLForResource:bundleResourceName
                                  withExtension:bundleResourceExtension
                                   subdirectory:bundleResourceSubdirectory];
}

+ (NSString *)bundleAssetsPath
{
    NSString *resourcePath = [bundleResourceBundle resourcePath];
    if (bundleResourceSubdirectory) {
        resourcePath = [resourcePath stringByAppendingPathComponent:bundleResourceSubdirectory];
    }

    return [resourcePath stringByAppendingPathComponent:[HotUpdateUpdateUtils assetsFolderName]];
}

+ (NSURL *)bundleURL
{
    return [self bundleURLForResource:bundleResourceName
                        withExtension:bundleResourceExtension
                         subdirectory:bundleResourceSubdirectory
                               bundle:bundleResourceBundle];
}

+ (NSURL *)bundleURLForResource:(NSString *)resourceName
{
    return [self bundleURLForResource:resourceName
                        withExtension:bundleResourceExtension
                         subdirectory:bundleResourceSubdirectory
                               bundle:bundleResourceBundle];
}

+ (NSURL *)bundleURLForResource:(NSString *)resourceName
                  withExtension:(NSString *)resourceExtension
{
    return [self bundleURLForResource:resourceName
                        withExtension:resourceExtension
                         subdirectory:bundleResourceSubdirectory
                               bundle:bundleResourceBundle];
}

+ (NSURL *)bundleURLForResource:(NSString *)resourceName
                  withExtension:(NSString *)resourceExtension
                   subdirectory:(NSString *)resourceSubdirectory
{
    return [self bundleURLForResource:resourceName
                        withExtension:resourceExtension
                         subdirectory:resourceSubdirectory
                               bundle:bundleResourceBundle];
}

+ (NSURL *)bundleURLForResource:(NSString *)resourceName
                  withExtension:(NSString *)resourceExtension
                   subdirectory:(NSString *)resourceSubdirectory
                         bundle:(NSBundle *)resourceBundle
{
    bundleResourceName = resourceName;
    bundleResourceExtension = resourceExtension;
    bundleResourceSubdirectory = resourceSubdirectory;
    bundleResourceBundle = resourceBundle;

    [self ensureBinaryBundleExists];

    NSString *logMessageFormat = @"Loading JS bundle from %@";

    NSError *error;
    NSString *packageFile = [HotUpdatePackage getCurrentPackageBundlePath:&error];
    NSURL *binaryBundleURL = [self binaryBundleURL];

    if (error || !packageFile) {
        CPLog(logMessageFormat, binaryBundleURL);
        isRunningBinaryVersion = YES;
        return binaryBundleURL;
    }

    NSString *binaryAppVersion = [[HotUpdateConfig current] appVersion];
    NSDictionary *currentPackageMetadata = [HotUpdatePackage getCurrentPackage:&error];
    if (error || !currentPackageMetadata) {
        CPLog(logMessageFormat, binaryBundleURL);
        isRunningBinaryVersion = YES;
        return binaryBundleURL;
    }

    // NSString *packageDate = [currentPackageMetadata objectForKey:BinaryBundleDateKey];
    NSString *packageAppVersion = [currentPackageMetadata objectForKey:AppVersionKey];

    if ([HotUpdate isUsingTestConfiguration] ||[binaryAppVersion isEqualToString:packageAppVersion]) {
        // Return package file because it is newer than the app store binary's JS bundle
        NSURL *packageUrl = [[NSURL alloc] initFileURLWithPath:packageFile];
        CPLog(logMessageFormat, packageUrl);
        isRunningBinaryVersion = NO;
        return packageUrl;
    } else {
        BOOL isRelease = NO;
#ifndef DEBUG
        isRelease = YES;
#endif

        if (isRelease || ![binaryAppVersion isEqualToString:packageAppVersion]) {
            [HotUpdate clearUpdates];
        }

        CPLog(logMessageFormat, binaryBundleURL);
        isRunningBinaryVersion = YES;
        return binaryBundleURL;
    }
}

+ (NSString *)getApplicationSupportDirectory
{
    NSString *applicationSupportDirectory = [NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES) objectAtIndex:0];
    return applicationSupportDirectory;
}

// + (void)overrideAppVersion:(NSString *)appVersion
// {
//     [HotUpdateConfig current].appVersion = appVersion;
// }

// + (void)setDeploymentKey:(NSString *)deploymentKey
// {
//     [HotUpdateConfig current].deploymentKey = deploymentKey;
// }

#pragma mark - Test-only methods

/*
 * WARNING: This cleans up all downloaded and pending updates.
 */
+ (void)clearUpdates
{
    [HotUpdatePackage clearUpdates];
    [self removePendingUpdate];
    [self removeFailedUpdates];
}

/*
 * This returns a boolean value indicating whether HotUpdate has
 * been set to run under a test configuration.
 */
+ (BOOL)isUsingTestConfiguration
{
    return testConfigurationFlag;
}

/*
 * This is used to enable an environment in which tests can be run.
 * Specifically, it flips a boolean flag that causes bundles to be
 * saved to a test folder and enables the ability to modify
 * installed bundles on the fly from JavaScript.
 */
+ (void)setUsingTestConfiguration:(BOOL)shouldUseTestConfiguration
{
    testConfigurationFlag = shouldUseTestConfiguration;
}

#pragma mark - Private API methods

@synthesize methodQueue = _methodQueue;
@synthesize pauseCallback = _pauseCallback;
@synthesize paused = _paused;

- (void)setPaused:(BOOL)paused
{
    if (_paused != paused) {
        _paused = paused;
        if (_pauseCallback) {
            _pauseCallback();
        }
    }
}

/*
 * This method is used to clear updates that are installed
 * under a different app version and hence don't apply anymore,
 * during a debug run configuration and when the bridge is
 * running the JS bundle from the dev server.
 */
- (void)clearDebugUpdates
{
    dispatch_async(dispatch_get_main_queue(), ^{
        if ([super.bridge.bundleURL.scheme hasPrefix:@"http"]) {
            NSError *error;
            NSString *binaryAppVersion = [[HotUpdateConfig current] appVersion];
            NSDictionary *currentPackageMetadata = [HotUpdatePackage getCurrentPackage:&error];
            if (currentPackageMetadata) {
                NSString *packageAppVersion = [currentPackageMetadata objectForKey:AppVersionKey];
                if (![binaryAppVersion isEqualToString:packageAppVersion]) {
                    [HotUpdate clearUpdates];
                }
            }
        }
    });
}

/*
 * This method is used by the React Native bridge to allow
 * our plugin to expose constants to the JS-side. In our case
 * we're simply exporting enum values so that the JS and Native
 * sides of the plugin can be in sync.
 */
- (NSDictionary *)constantsToExport
{
    // Export the values of the HotUpdateInstallMode and HotUpdateUpdateState
    // enums so that the script-side can easily stay in sync
    return @{
             @"hotUpdateInstallModeOnNextRestart":@(HotUpdateInstallModeOnNextRestart),
             @"hotUpdateInstallModeImmediate": @(HotUpdateInstallModeImmediate),
             @"hotUpdateInstallModeOnNextResume": @(HotUpdateInstallModeOnNextResume),
             @"hotUpdateInstallModeOnNextSuspend": @(HotUpdateInstallModeOnNextSuspend),

             @"hotUpdateUpdateStateRunning": @(HotUpdateUpdateStateRunning),
             @"hotUpdateUpdateStatePending": @(HotUpdateUpdateStatePending),
             @"hotUpdateUpdateStateLatest": @(HotUpdateUpdateStateLatest)
            };
};

+ (BOOL)requiresMainQueueSetup
{
    return YES;
}

- (void)dealloc
{
    // Ensure the global resume handler is cleared, so that
    // this object isn't kept alive unnecessarily
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

- (void)dispatchDownloadProgressEvent {
  // Notify the script-side about the progress
  [self sendEventWithName:DownloadProgressEvent
                     body:@{
                       @"totalBytes" : [NSNumber
                           numberWithLongLong:_latestExpectedContentLength],
                       @"receivedBytes" : [NSNumber
                           numberWithLongLong:_latestReceivedConentLength]
                     }];
}

/*
 * This method ensures that the app was packaged with a JS bundle
 * file, and if not, it throws the appropriate exception.
 */
+ (void)ensureBinaryBundleExists
{
    if (![self binaryBundleURL]) {
        NSString *errorMessage;

    #ifdef DEBUG
        #if TARGET_IPHONE_SIMULATOR
            errorMessage = @"React Native doesn't generate your app's JS bundle by default when deploying to the simulator. "
            "If you'd like to test HotUpdate using the simulator, you can do one of the following depending on your "
            "React Native version and/or preferred workflow:\n\n"

            "1. Update your AppDelegate.m file to load the JS bundle from the packager instead of from HotUpdate. "
            "You can still test your HotUpdate update experience using this workflow (Debug builds only).\n\n"

            "2. Force the JS bundle to be generated in simulator builds by adding 'export FORCE_BUNDLING=true' to the script under "
            "\"Build Phases\" > \"Bundle React Native code and images\" (React Native >=0.48 only).\n\n"

            "3. Force the JS bundle to be generated in simulator builds by removing the if block that echoes "
            "\"Skipping bundling for Simulator platform\" in the \"node_modules/react-native/packager/react-native-xcode.sh\" file (React Native <=0.47 only)\n\n"

            "4. Deploy a Release build to the simulator, which unlike Debug builds, will generate the JS bundle (React Native >=0.22.0 only).";
        #else
            errorMessage = [NSString stringWithFormat:@"The specified JS bundle file wasn't found within the app's binary. Is \"%@\" the correct file name?", [bundleResourceName stringByAppendingPathExtension:bundleResourceExtension]];
        #endif
    #else
        errorMessage = @"Something went wrong. Please verify if generated JS bundle is correct. ";
    #endif

        RCTFatal([HotUpdateErrorUtils errorWithMessage:errorMessage]);
    }
}

- (instancetype)init
{
    self = [super init];

    if (self) {
        [self initializeUpdateAfterRestart];
    }

    return self;
}

/*
 * This method is used when the app is started to either
 * initialize a pending update or rollback a faulty update
 * to the previous version.
 */
- (void)initializeUpdateAfterRestart
{
#ifdef DEBUG
    [self clearDebugUpdates];
#endif
    self.paused = YES;
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    NSDictionary *pendingUpdate = [preferences objectForKey:PendingUpdateKey];
    if (pendingUpdate) {
        _isFirstRunAfterUpdate = YES;
        BOOL updateIsLoading = [pendingUpdate[PendingUpdateIsLoadingKey] boolValue];
        if (updateIsLoading) {
            // Pending update was initialized, but notifyApplicationReady was not called.
            // Therefore, deduce that it is a broken update and rollback.
            CPLog(@"Update did not finish loading the last time, rolling back to a previous version.");
            needToReportRollback = YES;
            [self rollbackPackage];
        } else {
            // Mark that we tried to initialize the new update, so that if it crashes,
            // we will know that we need to rollback when the app next starts.
            [self savePendingUpdate:pendingUpdate[PendingUpdateHashKey]
                          isLoading:YES];
        }
    }
}

/*
 * This method checks to see whether a specific package hash
 * has previously failed installation.
 */
+ (BOOL)isFailedHash:(NSString*)packageHash
{
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    NSMutableArray *failedUpdates = [preferences objectForKey:FailedUpdatesKey];
    if (failedUpdates == nil || packageHash == nil) {
        return NO;
    } else {
        for (NSDictionary *failedPackage in failedUpdates)
        {
            // Type check is needed for backwards compatibility, where we used to just store
            // the failed package hash instead of the metadata. This only impacts "dev"
            // scenarios, since in production we clear out old information whenever a new
            // binary is applied.
            if ([failedPackage isKindOfClass:[NSDictionary class]]) {
                NSString *failedPackageHash = [failedPackage objectForKey:PackageHashKey];
                if ([packageHash isEqualToString:failedPackageHash]) {
                    return YES;
                }
            }
        }

        return NO;
    }
}

/*
 * This method checks to see whether a specific package hash
 * represents a downloaded and installed update, that hasn't
 * been applied yet via an app restart.
 */
+ (BOOL)isPendingUpdate:(NSString*)packageHash
{
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    NSDictionary *pendingUpdate = [preferences objectForKey:PendingUpdateKey];

    // If there is a pending update whose "state" isn't loading, then we consider it "pending".
    // Additionally, if a specific hash was provided, we ensure it matches that of the pending update.
    BOOL updateIsPending = pendingUpdate &&
                           [pendingUpdate[PendingUpdateIsLoadingKey] boolValue] == NO &&
                           (!packageHash || [pendingUpdate[PendingUpdateHashKey] isEqualToString:packageHash]);

    return updateIsPending;
}

/*
 * This method updates the React Native bridge's bundle URL
 * to point at the latest HotUpdate update, and then restarts
 * the bridge. This isn't meant to be called directly.
 */
- (void)loadBundle
{
    // This needs to be async dispatched because the bridge is not set on init
    // when the app first starts, therefore rollbacks will not take effect.
    dispatch_async(dispatch_get_main_queue(), ^{
        // If the current bundle URL is using http(s), then assume the dev
        // is debugging and therefore, shouldn't be redirected to a local
        // file (since Chrome wouldn't support it). Otherwise, update
        // the current bundle URL to point at the latest update
        if ([HotUpdate isUsingTestConfiguration] || ![super.bridge.bundleURL.scheme hasPrefix:@"http"]) {
            [super.bridge setValue:[HotUpdate bundleURL] forKey:@"bundleURL"];
        }

        [super.bridge reload];
    });
}

/*
 * This method is used when an update has failed installation
 * and the app needs to be rolled back to the previous bundle.
 * This method is automatically called when the rollback timer
 * expires without the app indicating whether the update succeeded,
 * and therefore, it shouldn't be called directly.
 */
- (void)rollbackPackage
{
    NSError *error;
    NSDictionary *failedPackage = [HotUpdatePackage getCurrentPackage:&error];
    if (!failedPackage) {
        if (error) {
            CPLog(@"Error getting current update metadata during rollback: %@", error);
        } else {
            CPLog(@"Attempted to perform a rollback when there is no current update");
        }
    } else {
        // Write the current package's metadata to the "failed list"
        [self saveFailedUpdate:failedPackage];
    }

    // Rollback to the previous version and de-register the new update
    [HotUpdatePackage rollbackPackage];
    [HotUpdate removePendingUpdate];
    [self loadBundle];
}

/*
 * When an update failed to apply, this method can be called
 * to store its hash so that it can be ignored on future
 * attempts to check the server for an update.
 */
- (void)saveFailedUpdate:(NSDictionary *)failedPackage
{
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    NSMutableArray *failedUpdates = [preferences objectForKey:FailedUpdatesKey];
    if (failedUpdates == nil) {
        failedUpdates = [[NSMutableArray alloc] init];
    } else {
        // The NSUserDefaults sytem always returns immutable
        // objects, regardless if you stored something mutable.
        failedUpdates = [failedUpdates mutableCopy];
    }

    [failedUpdates addObject:failedPackage];
    [preferences setObject:failedUpdates forKey:FailedUpdatesKey];
    [preferences synchronize];
}

/*
 * This method is used to clear away failed updates in the event that
 * a new app store binary is installed.
 */
+ (void)removeFailedUpdates
{
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    [preferences removeObjectForKey:FailedUpdatesKey];
    [preferences synchronize];
}

/*
 * This method is used to register the fact that a pending
 * update succeeded and therefore can be removed.
 */
+ (void)removePendingUpdate
{
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    [preferences removeObjectForKey:PendingUpdateKey];
    [preferences synchronize];
}

/*
 * When an update is installed whose mode isn't IMMEDIATE, this method
 * can be called to store the pending update's metadata (e.g. packageHash)
 * so that it can be used when the actual update application occurs at a later point.
 */
- (void)savePendingUpdate:(NSString *)packageHash
                isLoading:(BOOL)isLoading
{
    // Since we're not restarting, we need to store the fact that the update
    // was installed, but hasn't yet become "active".
    NSUserDefaults *preferences = [NSUserDefaults standardUserDefaults];
    NSDictionary *pendingUpdate = [[NSDictionary alloc] initWithObjectsAndKeys:
                                   packageHash,PendingUpdateHashKey,
                                   [NSNumber numberWithBool:isLoading],PendingUpdateIsLoadingKey, nil];

    [preferences setObject:pendingUpdate forKey:PendingUpdateKey];
    [preferences synchronize];
}

- (NSArray<NSString *> *)supportedEvents {
    return @[DownloadProgressEvent];
}

#pragma mark - Application lifecycle event handlers

// These two handlers will only be registered when there is
// a resume-based update still pending installation.
// - (void)applicationWillEnterForeground
// {
//     if (_appSuspendTimer) {
//         [_appSuspendTimer invalidate];
//         _appSuspendTimer = nil;
//     }
//     // Determine how long the app was in the background and ensure
//     // that it meets the minimum duration amount of time.
//     int durationInBackground = 0;
//     if (_lastResignedDate) {
//         durationInBackground = [[NSDate date] timeIntervalSinceDate:_lastResignedDate];
//     }

//     if (durationInBackground >= _minimumBackgroundDuration) {
//         [self loadBundle];
//     }
// }

// - (void)applicationWillResignActive
// {
//     // Save the current time so that when the app is later
//     // resumed, we can detect how long it was in the background.
//     _lastResignedDate = [NSDate date];

//     if (_installMode == HotUpdateInstallModeOnNextSuspend && [[self class] isPendingUpdate:nil]) {
//         _appSuspendTimer = [NSTimer scheduledTimerWithTimeInterval:_minimumBackgroundDuration
//                                                          target:self
//                                                        selector:@selector(loadBundleOnTick:)
//                                                        userInfo:nil
//                                                         repeats:NO];
//     }
// }

// -(void)loadBundleOnTick:(NSTimer *)timer {
//     [self loadBundle];
// }

// #pragma mark - JavaScript-exported module methods (Public)

/*
 * This is native-side of the RemotePackage.download method
 */
RCT_EXPORT_METHOD(downloadUpdate:(NSDictionary*)updatePackage
                  notifyProgress:(BOOL)notifyProgress
                        resolver:(RCTPromiseResolveBlock)resolve
                        rejecter:(RCTPromiseRejectBlock)reject)
{
    NSDictionary *mutableUpdatePackage = [updatePackage mutableCopy];
    NSURL *binaryBundleURL = [HotUpdate binaryBundleURL];
    if (binaryBundleURL != nil) {
        [mutableUpdatePackage setValue:[HotUpdateUpdateUtils modifiedDateStringOfFileAtURL:binaryBundleURL]
                                forKey:BinaryBundleDateKey];
    }

    if (notifyProgress) {
        // Set up and unpause the frame observer so that it can emit
        // progress events every frame if the progress is updated.
        _didUpdateProgress = NO;
        self.paused = NO;
    }

    [HotUpdatePackage
        downloadPackage:mutableUpdatePackage
        expectedBundleFileName:[bundleResourceName stringByAppendingPathExtension:bundleResourceExtension] 
        operationQueue:_methodQueue
        // The download is progressing forward
        progressCallback:^(long long expectedContentLength, long long receivedContentLength) {
            // Update the download progress so that the frame observer can notify the JS side
            _latestExpectedContentLength = expectedContentLength;
            _latestReceivedConentLength = receivedContentLength;
            _didUpdateProgress = YES;

            // If the download is completed, stop observing frame
            // updates and synchronously send the last event.
            if (expectedContentLength == receivedContentLength) {
                _didUpdateProgress = NO;
                self.paused = YES;
                [self dispatchDownloadProgressEvent];
            }
        }
        // The download completed
        doneCallback:^{
            NSError *err;
            NSDictionary *newPackage = [HotUpdatePackage getPackage:mutableUpdatePackage[PackageHashKey] error:&err];

            if (err) {
                return reject([NSString stringWithFormat: @"%lu", (long)err.code], err.localizedDescription, err);
            }
            resolve(newPackage);
        }
        // The download failed
        failCallback:^(NSError *err) {
            if ([HotUpdateErrorUtils isHotUpdateError:err]) {
                [self saveFailedUpdate:mutableUpdatePackage];
            }

            // Stop observing frame updates if the download fails.
            _didUpdateProgress = NO;
            self.paused = YES;
            reject([NSString stringWithFormat: @"%lu", (long)err.code], err.localizedDescription, err);
        }];
}

/*
 * 获取原生版本号、当前热更补丁号、当前热更哈希
 */
RCT_EXPORT_METHOD(getCurrentVersionInfo:(RCTPromiseResolveBlock)resolve
                          rejecter:(RCTPromiseRejectBlock)reject)
{
    NSString *appVersion = [[HotUpdateConfig current] appVersion];
	NSMutableDictionary *response = [NSMutableDictionary dictionary];
	[response setObject:appVersion forKey:@"appVersion"];

	NSError *error;
    NSMutableDictionary *currentPackageInfo = [[HotUpdatePackage getCurrentPackage:&error] mutableCopy];
    if (error) {
        return reject([NSString stringWithFormat: @"%lu", (long)error.code], error.localizedDescription, error);
    } else if (currentPackageInfo == nil) {
        return resolve(response);
    }

    [response setObject:[currentPackageInfo objectForKey:AppPatchCode] forKey:@"appPatch"];
    [response setObject:[currentPackageInfo objectForKey:PackageHashKey] forKey:@"appHash"];

    resolve(response);
}

/*
 * This method is the native side of the HotUpdate.getUpdateMetadata method.
 */
// RCT_EXPORT_METHOD(getUpdateMetadata:(HotUpdateUpdateState)updateState
//                            resolver:(RCTPromiseResolveBlock)resolve
//                            rejecter:(RCTPromiseRejectBlock)reject)
// {
//     NSError *error;
//     NSMutableDictionary *package = [[HotUpdatePackage getCurrentPackage:&error] mutableCopy];

//     if (error) {
//         return reject([NSString stringWithFormat: @"%lu", (long)error.code], error.localizedDescription, error);
//     } else if (package == nil) {
//         // The app hasn't downloaded any HotUpdate updates yet,
//         // so we simply return nil regardless if the user
//         // wanted to retrieve the pending or running update.
//         return resolve(nil);
//     }

//     // We have a HotUpdate update, so let's see if it's currently in a pending state.
//     BOOL currentUpdateIsPending = [[self class] isPendingUpdate:[package objectForKey:PackageHashKey]];

//     if (updateState == HotUpdateUpdateStatePending && !currentUpdateIsPending) {
//         // The caller wanted a pending update
//         // but there isn't currently one.
//         resolve(nil);
//     } else if (updateState == HotUpdateUpdateStateRunning && currentUpdateIsPending) {
//         // The caller wants the running update, but the current
//         // one is pending, so we need to grab the previous.
//         resolve([HotUpdatePackage getPreviousPackage:&error]);
//     } else {
//         // The current package satisfies the request:
//         // 1) Caller wanted a pending, and there is a pending update
//         // 2) Caller wanted the running update, and there isn't a pending
//         // 3) Caller wants the latest update, regardless if it's pending or not
//         if (isRunningBinaryVersion) {
//             // This only matters in Debug builds. Since we do not clear "outdated" updates,
//             // we need to indicate to the JS side that somehow we have a current update on
//             // disk that is not actually running.
//             [package setObject:@(YES) forKey:@"_isDebugOnly"];
//         }

//         // Enable differentiating pending vs. non-pending updates
//         [package setObject:@(currentUpdateIsPending) forKey:PackageIsPendingKey];
//         resolve(package);
//     }
// }

/*
 * This method is the native side of the LocalPackage.install method.
 */
RCT_EXPORT_METHOD(installUpdate:(NSDictionary*)updatePackage
                    installMode:(HotUpdateInstallMode)installMode
      minimumBackgroundDuration:(int)minimumBackgroundDuration
                       resolver:(RCTPromiseResolveBlock)resolve
                       rejecter:(RCTPromiseRejectBlock)reject)
{
    NSError *error;
    [HotUpdatePackage installPackage:updatePackage
                removePendingUpdate:[[self class] isPendingUpdate:nil]
                              error:&error];

    if (error) {
        reject([NSString stringWithFormat: @"%lu", (long)error.code], error.localizedDescription, error);
    } else {
        [self savePendingUpdate:updatePackage[PackageHashKey]
                      isLoading:NO];

        _installMode = installMode;
        if (_installMode == HotUpdateInstallModeOnNextResume || _installMode == HotUpdateInstallModeOnNextSuspend) {
            _minimumBackgroundDuration = minimumBackgroundDuration;

            if (!_hasResumeListener) {
                // Ensure we do not add the listener twice.
                // Register for app resume notifications so that we
                // can check for pending updates which support "restart on resume"
                [[NSNotificationCenter defaultCenter] addObserver:self
                                                         selector:@selector(applicationWillEnterForeground)
                                                             name:UIApplicationWillEnterForegroundNotification
                                                           object:RCTSharedApplication()];

                [[NSNotificationCenter defaultCenter] addObserver:self
                                                         selector:@selector(applicationWillResignActive)
                                                             name:UIApplicationWillResignActiveNotification
                                                           object:RCTSharedApplication()];

                _hasResumeListener = YES;
            }
        }

        // Signal to JS that the update has been applied.
        resolve(nil);
    }
}

/*
 * This method isn't publicly exposed via the "react-native-code-push"
 * module, and is only used internally to populate the RemotePackage.failedInstall property.
 */
// RCT_EXPORT_METHOD(isFailedUpdate:(NSString *)packageHash
//                          resolve:(RCTPromiseResolveBlock)resolve
//                           reject:(RCTPromiseRejectBlock)reject)
// {
//     BOOL isFailedHash = [[self class] isFailedHash:packageHash];
//     resolve(@(isFailedHash));
// }

/*
 * This method isn't publicly exposed via the "react-native-code-push"
 * module, and is only used internally to populate the LocalPackage.isFirstRun property.
 */
// RCT_EXPORT_METHOD(isFirstRun:(NSString *)packageHash
//                      resolve:(RCTPromiseResolveBlock)resolve
//                     rejecter:(RCTPromiseRejectBlock)reject)
// {
//     NSError *error;
//     BOOL isFirstRun = _isFirstRunAfterUpdate
//                         && nil != packageHash
//                         && [packageHash length] > 0
//                         && [packageHash isEqualToString:[HotUpdatePackage getCurrentPackageHash:&error]];

//     resolve(@(isFirstRun));
// }

/*
 * This method is the native side of the HotUpdate.notifyApplicationReady() method.
 */
RCT_EXPORT_METHOD(notifyApplicationReady:(RCTPromiseResolveBlock)resolve
                                rejecter:(RCTPromiseRejectBlock)reject)
{
    [HotUpdate removePendingUpdate];
    resolve(nil);
}

/*
 * 获取当前热更包信息
 */
RCT_EXPORT_METHOD(getStat:(RCTPromiseResolveBlock)resolve
                                rejecter:(RCTPromiseRejectBlock)reject)
{
    NSError *error;
    NSMutableDictionary *hotUpdateJson = [HotUpdatePackage getCurrentPackageInfo:&error];
    if (error) {
        reject([NSString stringWithFormat: @"%lu", (long)error.code], error.localizedDescription, error);
    } else {
        resolve(hotUpdateJson);
    }
}

/*
 * This method is the native side of the HotUpdate.restartApp() method.
 */
RCT_EXPORT_METHOD(restartApp:(BOOL)onlyIfUpdateIsPending
                     resolve:(RCTPromiseResolveBlock)resolve
                    rejecter:(RCTPromiseRejectBlock)reject)
{
    // If this is an unconditional restart request, or there
    // is current pending update, then reload the app.
    if (!onlyIfUpdateIsPending || [[self class] isPendingUpdate:nil]) {
        [self loadBundle];
        resolve(@(YES));
        return;
    }

    resolve(@(NO));
}

// #pragma mark - JavaScript-exported module methods (Private)

// /*
//  * This method is the native side of the HotUpdate.downloadAndReplaceCurrentBundle()
//  * method, which replaces the current bundle with the one downloaded from
//  * removeBundleUrl. It is only to be used during tests and no-ops if the test
//  * configuration flag is not set.
//  */
// RCT_EXPORT_METHOD(downloadAndReplaceCurrentBundle:(NSString *)remoteBundleUrl)
// {
//     if ([HotUpdate isUsingTestConfiguration]) {
//         [HotUpdatePackage downloadAndReplaceCurrentBundle:remoteBundleUrl];
//     }
// }


#pragma mark - RCTFrameUpdateObserver Methods

- (void)didUpdateFrame:(RCTFrameUpdate *)update
{
    if (!_didUpdateProgress) {
        return;
    }

    [self dispatchDownloadProgressEvent];
    _didUpdateProgress = NO;
}

@end