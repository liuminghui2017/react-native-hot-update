#import "HotUpdate.h"

#if __has_include(<React/RCTConvert.h>)
#import <React/RCTConvert.h>
#else
#import "RCTConvert.h"
#endif

// Extending the RCTConvert class allows the React Native
// bridge to handle args of type "HotUpdateInstallMode"
@implementation RCTConvert (HotUpdateInstallMode)

RCT_ENUM_CONVERTER(HotUpdateInstallMode, (@{ @"hotUpdateInstallModeImmediate": @(HotUpdateInstallModeImmediate),
                                            @"hotUpdateInstallModeOnNextRestart": @(HotUpdateInstallModeOnNextRestart),
                                            @"hotUpdateInstallModeOnNextResume": @(HotUpdateInstallModeOnNextResume),
                                            @"hotUpdateInstallModeOnNextSuspend": @(HotUpdateInstallModeOnNextSuspend) }),
                   HotUpdateInstallModeImmediate, // Default enum value
                   integerValue)

@end
