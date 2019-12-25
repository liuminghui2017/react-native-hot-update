#import "HotUpdate.h"

#if __has_include(<React/RCTConvert.h>)
#import <React/RCTConvert.h>
#else
#import "RCTConvert.h"
#endif

// Extending the RCTConvert class allows the React Native
// bridge to handle args of type "HotUpdateUpdateState"
@implementation RCTConvert (HotUpdateUpdateState)

RCT_ENUM_CONVERTER(HotUpdateUpdateState, (@{ @"hotUpdateUpdateStateRunning": @(HotUpdateUpdateStateRunning),
                                            @"hotUpdateUpdateStatePending": @(HotUpdateUpdateStatePending),
                                            @"hotUpdateUpdateStateLatest": @(HotUpdateUpdateStateLatest)
                                          }),
                   HotUpdateUpdateStateRunning, // Default enum value
                   integerValue)

@end
