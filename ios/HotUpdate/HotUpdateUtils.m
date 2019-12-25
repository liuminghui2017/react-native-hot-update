#import "HotUpdate.h"

void CPLog(NSString *formatString, ...) {
    va_list args;
    va_start(args, formatString);
    NSString *prependedFormatString = [NSString stringWithFormat:@"\n[HotUpdate] %@", formatString];
    NSLogv(prependedFormatString, args);
    va_end(args);
}