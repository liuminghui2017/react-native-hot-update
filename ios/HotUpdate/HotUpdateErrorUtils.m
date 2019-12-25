#import "HotUpdate.h"

@implementation HotUpdateErrorUtils

static NSString *const HotUpdateErrorDomain = @"HotUpdateError";
static const int HotUpdateErrorCode = -1;

+ (NSError *)errorWithMessage:(NSString *)errorMessage
{
    return [NSError errorWithDomain:HotUpdateErrorDomain
                               code:HotUpdateErrorCode
                           userInfo:@{ NSLocalizedDescriptionKey: NSLocalizedString(errorMessage, nil) }];
}

+ (BOOL)isHotUpdateError:(NSError *)err
{
    return err != nil && [HotUpdateErrorDomain isEqualToString:err.domain];
}

@end