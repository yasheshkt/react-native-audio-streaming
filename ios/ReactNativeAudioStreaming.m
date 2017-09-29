#import "RCTBridgeModule.h"
#import "RCTEventDispatcher.h"

#import "ReactNativeAudioStreaming.h"

#define LPN_AUDIO_BUFFER_SEC 20 // Can't use this with shoutcast buffer meta data

@import AVFoundation;
@import MediaPlayer;

@interface ReactNativeAudioStreaming ()
@property (nonatomic, retain) NSString *currentTrackTitle;
@property (nonatomic, retain) NSString *currentArtist;
@property (nonatomic, retain) NSString *currentCoverImageUrl;
@property (nonatomic, assign) bool isPlaying;
@property (nonatomic, strong) UIImage *currentCoverImage;
@end

@implementation ReactNativeAudioStreaming

@synthesize bridge = _bridge;

RCT_EXPORT_MODULE()
- (dispatch_queue_t)methodQueue
{
   return dispatch_get_main_queue();
}

- (ReactNativeAudioStreaming *)init
{
   self = [super init];
   if (self) {
      [self setSharedAudioSessionCategory];
      self.audioPlayer = [[STKAudioPlayer alloc] initWithOptions:(STKAudioPlayerOptions){ .flushQueueOnSeek = YES }];
      [self.audioPlayer setDelegate:self];
      self.lastUrlString = @"";
      [NSTimer scheduledTimerWithTimeInterval:0.5 target:self selector:@selector(tick:) userInfo:nil repeats:YES];
      
      NSLog(@"AudioPlayer initialized");
   }
   
   return self;
}


-(void) tick:(NSTimer*)timer
{
   if (!self.audioPlayer) {
      return;
   }
   
   if (self.audioPlayer.currentlyPlayingQueueItemId != nil && self.audioPlayer.state == STKAudioPlayerStatePlaying) {
      NSNumber *progress = [NSNumber numberWithFloat:self.audioPlayer.progress];
      NSNumber *duration = [NSNumber numberWithFloat:self.audioPlayer.duration];
      NSString *url = [NSString stringWithString:self.audioPlayer.currentlyPlayingQueueItemId];
      [self updateNowPlayingInfo];
      
      [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent" body:@{
                                                                                      @"status": @"STREAMING",
                                                                                      @"progress": progress,
                                                                                      @"duration": duration,
                                                                                      @"url": url,
                                                                                      }];
   }
}


- (void)dealloc
{
   [self unregisterAudioInterruptionNotifications];
   [self unregisterRemoteControlEvents];
   [self.audioPlayer setDelegate:nil];
}


#pragma mark - Public API

RCT_EXPORT_METHOD(play:(NSString *) streamUrl options:(NSDictionary *)options)
{
   if (!self.audioPlayer) {
      return;
   }
   
   [self activate];
   
   if (self.audioPlayer.state == STKAudioPlayerStatePaused && [self.lastUrlString isEqualToString:streamUrl]) {
      [self.audioPlayer resume];
   } else {
      [self.audioPlayer play:streamUrl];
   }
   
   self.lastUrlString = streamUrl;
   self.showNowPlayingInfo = false;
   
   if ([options objectForKey:@"showIniOSMediaCenter"]) {
      self.showNowPlayingInfo = [[options objectForKey:@"showIniOSMediaCenter"] boolValue];
   }
   
   if (self.showNowPlayingInfo) {
      //unregister any existing registrations
      [self unregisterAudioInterruptionNotifications];
      [self unregisterRemoteControlEvents];
      //register
      [self registerAudioInterruptionNotifications];
      [self registerRemoteControlEvents];
   }
   
   self.isPlaying = true;
   [self updateNowPlayingInfo];
}

RCT_EXPORT_METHOD(seekToTime:(double) seconds)
{
   if (!self.audioPlayer) {
      return;
   }
   
   [self.audioPlayer seekToTime:seconds];
}

RCT_EXPORT_METHOD(goForward:(double) seconds)
{
   if (!self.audioPlayer) {
      return;
   }
   
   double newtime = self.audioPlayer.progress + seconds;
   
   if (self.audioPlayer.duration < newtime) {
      [self.audioPlayer stop];
      self.isPlaying = false;
      [self updateNowPlayingInfo];
   } else {
      [self.audioPlayer seekToTime:newtime];
   }
}

RCT_EXPORT_METHOD(goBack:(double) seconds)
{
   if (!self.audioPlayer) {
      return;
   }
   
   double newtime = self.audioPlayer.progress - seconds;
   
   if (newtime < 0) {
      [self.audioPlayer seekToTime:0.0];
   } else {
      [self.audioPlayer seekToTime:newtime];
   }
}

RCT_EXPORT_METHOD(pause)
{
   if (!self.audioPlayer) {
      return;
   } else {
      [self.audioPlayer pause];
      self.isPlaying = false;
      [self updateNowPlayingInfo];
      [self deactivate];
   }
}

RCT_EXPORT_METHOD(resume)
{
   if (!self.audioPlayer) {
      return;
   } else {
      [self activate];
      [self.audioPlayer resume];
      self.isPlaying = true;
      [self updateNowPlayingInfo];
   }
}

RCT_EXPORT_METHOD(stop)
{
   if (!self.audioPlayer) {
      return;
   } else {
      [self.audioPlayer stop];
      self.isPlaying = false;
      [self updateNowPlayingInfo];
      [self deactivate];
   }
}

RCT_EXPORT_METHOD(getStatus: (RCTResponseSenderBlock) callback)
{
   NSString *status = @"STOPPED";
   NSNumber *duration = [NSNumber numberWithFloat:self.audioPlayer.duration];
   NSNumber *progress = [NSNumber numberWithFloat:self.audioPlayer.progress];
   
   if (!self.audioPlayer) {
      status = @"ERROR";
   } else if ([self.audioPlayer state] == STKAudioPlayerStatePlaying) {
      status = @"PLAYING";
   } else if ([self.audioPlayer state] == STKAudioPlayerStatePaused) {
      status = @"PAUSED";
   } else if ([self.audioPlayer state] == STKAudioPlayerStateBuffering) {
      status = @"BUFFERING";
   }
   
   callback(@[[NSNull null], @{@"status": status, @"progress": progress, @"duration": duration, @"url": self.lastUrlString}]);
}

RCT_EXPORT_METHOD(setTrackTitle:(NSString *) trackTitle)
{
   self.currentTrackTitle = trackTitle;
   [self updateNowPlayingInfo];
}

RCT_EXPORT_METHOD(setArtist:(NSString *) artist)
{
   self.currentArtist = artist;
   [self updateNowPlayingInfo];
}

RCT_EXPORT_METHOD(setCoverImageUrl:(NSString *) coverImageUrl)
{
   self.currentCoverImageUrl = coverImageUrl;
   [self updateNowPlayingInfo];
}

- (void)setCurrentCoverImageUrl:(NSString *)currentCoverImageUrl {
   if (currentCoverImageUrl && ![currentCoverImageUrl isEqualToString:self.currentCoverImageUrl]) {
      self.currentCoverImage = nil;
   }
   
   _currentCoverImageUrl = currentCoverImageUrl;
   if (self.currentCoverImageUrl) {
      [self downloadCoverImage:[NSURL URLWithString:self.currentCoverImageUrl]];
   }
}

#pragma mark - StreamingKit Audio Player


- (void)audioPlayer:(STKAudioPlayer *)player didStartPlayingQueueItemId:(NSObject *)queueItemId
{
   NSLog(@"AudioPlayer is playing");
}

- (void)audioPlayer:(STKAudioPlayer *)player didFinishPlayingQueueItemId:(NSObject *)queueItemId withReason:(STKAudioPlayerStopReason)stopReason andProgress:(double)progress andDuration:(double)duration
{
   NSLog(@"AudioPlayer has stopped");
}

- (void)audioPlayer:(STKAudioPlayer *)player didFinishBufferingSourceWithQueueItemId:(NSObject *)queueItemId
{
   NSLog(@"AudioPlayer finished buffering");
}

- (void)audioPlayer:(STKAudioPlayer *)player unexpectedError:(STKAudioPlayerErrorCode)errorCode {
   NSLog(@"AudioPlayer unexpected Error with code %d", errorCode);
}

- (void)audioPlayer:(STKAudioPlayer *)audioPlayer didReadStreamMetadata:(NSDictionary *)dictionary {
   NSLog(@"AudioPlayer SONG NAME  %@", dictionary[@"StreamTitle"]);
   
   NSString *trackTitleFromMetadata = dictionary[@"StreamTitle"] ? dictionary[@"StreamTitle"] : @"";
   [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent" body:@{
                                                                                   @"status": @"METADATA_UPDATED",
                                                                                   @"key": @"StreamTitle",
                                                                                   @"value": trackTitleFromMetadata
                                                                                   }];
   self.isPlaying = true;
   [self updateNowPlayingInfo];
}

- (void)audioPlayer:(STKAudioPlayer *)player stateChanged:(STKAudioPlayerState)state previousState:(STKAudioPlayerState)previousState
{
   NSNumber *duration = [NSNumber numberWithFloat:self.audioPlayer.duration];
   NSNumber *progress = [NSNumber numberWithFloat:self.audioPlayer.progress];
   
   switch (state) {
      case STKAudioPlayerStatePlaying:
         [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                         body:@{@"status": @"PLAYING", @"progress": progress, @"duration": duration, @"url": self.lastUrlString}];
         break;
         
      case STKAudioPlayerStatePaused:
         [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                         body:@{@"status": @"PAUSED", @"progress": progress, @"duration": duration, @"url": self.lastUrlString}];
         break;
         
      case STKAudioPlayerStateStopped:
         [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                         body:@{@"status": @"STOPPED", @"progress": progress, @"duration": duration, @"url": self.lastUrlString}];
         break;
         
      case STKAudioPlayerStateBuffering:
         [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                         body:@{@"status": @"BUFFERING"}];
         break;
         
      case STKAudioPlayerStateError:
         [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                         body:@{@"status": @"ERROR"}];
         break;
         
      default:
         break;
   }
}


#pragma mark - Audio Session

- (void)activate
{
   NSError *categoryError = nil;
   
   [[AVAudioSession sharedInstance] setActive:YES error:&categoryError];
   [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryPlayback error:&categoryError];
   
   if (categoryError) {
      NSLog(@"Error setting category! %@", [categoryError description]);
   }
}

- (void)deactivate
{
   NSError *categoryError = nil;
   
   [[AVAudioSession sharedInstance] setActive:NO error:&categoryError];
   
   if (categoryError) {
      NSLog(@"Error setting category! %@", [categoryError description]);
   }
}

- (void)setSharedAudioSessionCategory
{
   NSError *categoryError = nil;
   self.isPlayingWithOthers = [[AVAudioSession sharedInstance] isOtherAudioPlaying];
   
   [[AVAudioSession sharedInstance] setActive:NO error:&categoryError];
   [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryAmbient error:&categoryError];
   
   if (categoryError) {
      NSLog(@"Error setting category! %@", [categoryError description]);
   }
}

- (void)registerAudioInterruptionNotifications
{
   // Register for audio interrupt notifications
   [[NSNotificationCenter defaultCenter] addObserver:self
                                            selector:@selector(onAudioInterruption:)
                                                name:AVAudioSessionInterruptionNotification
                                              object:nil];
   // Register for route change notifications
   [[NSNotificationCenter defaultCenter] addObserver:self
                                            selector:@selector(onRouteChangeInterruption:)
                                                name:AVAudioSessionRouteChangeNotification
                                              object:nil];
}

- (void)unregisterAudioInterruptionNotifications
{
   [[NSNotificationCenter defaultCenter] removeObserver:self
                                                   name:AVAudioSessionRouteChangeNotification
                                                 object:nil];
   [[NSNotificationCenter defaultCenter] removeObserver:self
                                                   name:AVAudioSessionInterruptionNotification
                                                 object:nil];
}

- (void)onAudioInterruption:(NSNotification *)notification
{
   // Get the user info dictionary
   NSDictionary *interruptionDict = notification.userInfo;
   
   // Get the AVAudioSessionInterruptionTypeKey enum from the dictionary
   NSInteger interuptionType = [[interruptionDict valueForKey:AVAudioSessionInterruptionTypeKey] integerValue];
   
   // Decide what to do based on interruption type
   switch (interuptionType)
   {
      case AVAudioSessionInterruptionTypeBegan:
         NSLog(@"Audio Session Interruption case started.");
         [self.audioPlayer pause];
         break;
         
      case AVAudioSessionInterruptionTypeEnded:
         NSLog(@"Audio Session Interruption case ended.");
         self.isPlayingWithOthers = [[AVAudioSession sharedInstance] isOtherAudioPlaying];
         (self.isPlayingWithOthers) ? [self.audioPlayer stop] : [self.audioPlayer resume];
         break;
         
      default:
         NSLog(@"Audio Session Interruption Notification case default.");
         break;
   }
}

- (void)onRouteChangeInterruption:(NSNotification *)notification
{
   
   NSDictionary *interruptionDict = notification.userInfo;
   NSInteger routeChangeReason = [[interruptionDict valueForKey:AVAudioSessionRouteChangeReasonKey] integerValue];
   
   switch (routeChangeReason)
   {
      case AVAudioSessionRouteChangeReasonUnknown:
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonUnknown");
         break;
         
      case AVAudioSessionRouteChangeReasonNewDeviceAvailable:
         // A user action (such as plugging in a headset) has made a preferred audio route available.
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonNewDeviceAvailable");
         break;
         
      case AVAudioSessionRouteChangeReasonOldDeviceUnavailable:
         // The previous audio output path is no longer available.
         [self.audioPlayer stop];
         break;
         
      case AVAudioSessionRouteChangeReasonCategoryChange:
         // The category of the session object changed. Also used when the session is first activated.
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonCategoryChange"); //AVAudioSessionRouteChangeReasonCategoryChange
         break;
         
      case AVAudioSessionRouteChangeReasonOverride:
         // The output route was overridden by the app.
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonOverride");
         break;
         
      case AVAudioSessionRouteChangeReasonWakeFromSleep:
         // The route changed when the device woke up from sleep.
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonWakeFromSleep");
         break;
         
      case AVAudioSessionRouteChangeReasonNoSuitableRouteForCategory:
         // The route changed because no suitable route is now available for the specified category.
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonNoSuitableRouteForCategory");
         break;
   }
}

#pragma mark - Remote Control Events

- (void)registerRemoteControlEvents
{
   MPRemoteCommandCenter *commandCenter = [MPRemoteCommandCenter sharedCommandCenter];
   
   MPSkipIntervalCommand *skipBackwardIntervalCommand = [commandCenter skipBackwardCommand];
   [skipBackwardIntervalCommand setEnabled:YES];
   [skipBackwardIntervalCommand removeTarget:self action:@selector(didReceiveSeekBackwardCommand:)];
   [skipBackwardIntervalCommand addTarget:self action:@selector(didReceiveSeekBackwardCommand:)];
   skipBackwardIntervalCommand.preferredIntervals = @[@(15)];  // Set your own interval
   
   MPSkipIntervalCommand *skipForwardIntervalCommand = [commandCenter skipForwardCommand];
   skipForwardIntervalCommand.preferredIntervals = @[@(15)];  // Max 99
   [skipForwardIntervalCommand setEnabled:YES];
   [skipForwardIntervalCommand removeTarget:self action:@selector(didReceiveSeekForwardCommand:)];
   [skipForwardIntervalCommand addTarget:self action:@selector(didReceiveSeekForwardCommand:)];
   
   MPRemoteCommand *pauseCommand = [commandCenter pauseCommand];
   [pauseCommand setEnabled:YES];
   [pauseCommand removeTarget:self action:@selector(didReceivePauseCommand:)];
   [pauseCommand addTarget:self action:@selector(didReceivePauseCommand:)];
   //
   MPRemoteCommand *playCommand = [commandCenter playCommand];
   [playCommand setEnabled:YES];
   [playCommand removeTarget:self action:@selector(didReceivePlayCommand:)];
   [playCommand addTarget:self action:@selector(didReceivePlayCommand:)];
   
   MPRemoteCommand *togglePlayPauseCommand = [commandCenter togglePlayPauseCommand];
   [togglePlayPauseCommand setEnabled:YES];
   [togglePlayPauseCommand removeTarget:self action:@selector(didReceiveTogglePlayPauseCommand:)];
   [togglePlayPauseCommand addTarget:self action:@selector(didReceiveTogglePlayPauseCommand:)];
   
   MPRemoteCommand *nextTrackCommand = [commandCenter nextTrackCommand];
   [nextTrackCommand setEnabled:YES];
   [nextTrackCommand removeTarget:self action:@selector(didReceiveNextTrackCommand:)];
   [nextTrackCommand addTarget:self action:@selector(didReceiveNextTrackCommand:)];
   
   MPRemoteCommand *previousTrackCommand = [commandCenter previousTrackCommand];
   [previousTrackCommand setEnabled:YES];
   [previousTrackCommand removeTarget:self action:@selector(didReceivePreviousTrackCommand:)];
   [previousTrackCommand addTarget:self action:@selector(didReceivePreviousTrackCommand:)];
}

- (MPRemoteCommandHandlerStatus)didReceivePlayCommand:(MPRemoteCommand *)event
{
   NSLog(@"didReceivePlayCommand");
   [self resume];
   return MPRemoteCommandHandlerStatusSuccess;
}

- (MPRemoteCommandHandlerStatus)didReceivePauseCommand:(MPRemoteCommand *)event
{
   NSLog(@"didReceivePauseCommand");
   [self pause];
   return MPRemoteCommandHandlerStatusSuccess;
}

- (MPRemoteCommandHandlerStatus)didReceiveTogglePlayPauseCommand:(MPRemoteCommand *)event
{
   NSLog(@"didReceiveTogglePlayPauseCommand");
   if (self.isPlaying) {
      [self pause];
   } else {
      [self resume];
   }
   return MPRemoteCommandHandlerStatusSuccess;
}


- (MPRemoteCommandHandlerStatus)didReceiveSeekForwardCommand:(MPRemoteCommand *)event
{
   NSLog(@"didReceiveSeekForwardCommand");
   [self goForward:15];
   return MPRemoteCommandHandlerStatusSuccess;
}

- (MPRemoteCommandHandlerStatus)didReceiveSeekBackwardCommand:(MPRemoteCommand *)event
{
   NSLog(@"didReceiveSeekBackwardCommand");
   [self goBack:15];
   return MPRemoteCommandHandlerStatusSuccess;
}

- (MPRemoteCommandHandlerStatus)didReceiveNextTrackCommand:(MPRemoteCommand *)event
{
   NSLog(@"didReceiveNextTrackCommand");
   [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                   body:@{@"status": @"METADATA_UPDATED", @"command": @"didReceiveNextTrackCommand"}];
   return MPRemoteCommandHandlerStatusSuccess;
}

- (MPRemoteCommandHandlerStatus)didReceivePreviousTrackCommand:(MPRemoteCommand *)event
{
   NSLog(@"didReceivePreviousTrackCommand");
   [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                   body:@{@"status": @"METADATA_UPDATED", @"command": @"didReceivePreviousTrackCommand"}];
   return MPRemoteCommandHandlerStatusSuccess;
}

- (void)unregisterRemoteControlEvents
{
   MPRemoteCommandCenter *commandCenter = [MPRemoteCommandCenter sharedCommandCenter];
   [commandCenter.playCommand removeTarget:self];
   [commandCenter.pauseCommand removeTarget:self];
}

- (void)downloadCoverImage:(NSURL *)url {
   [[NSURLSession.sharedSession dataTaskWithURL:url completionHandler:^(NSData * _Nullable data, NSURLResponse * _Nullable response, NSError * _Nullable error) {
      if (!error) {
         self.currentCoverImage = [UIImage imageWithData:data];
         [self updateNowPlayingInfo];
      }
   }] resume];
}

- (void)updateNowPlayingInfo
{
   if (self.showNowPlayingInfo) {
      
      NSNumber *progress = [NSNumber numberWithFloat:self.audioPlayer.progress];
      NSNumber *duration = [NSNumber numberWithFloat:self.audioPlayer.duration];
      
      NSMutableDictionary *nowPlayingInfo = [@{MPMediaItemPropertyTitle: self.currentTrackTitle ? self.currentTrackTitle : @"",
                                               MPMediaItemPropertyArtist: self.currentArtist ? self.currentArtist : @"",
                                               MPNowPlayingInfoPropertyPlaybackRate: self.isPlaying ? @1.0f : @0.0,
                                               MPMediaItemPropertyPlaybackDuration: duration,
                                               MPNowPlayingInfoPropertyElapsedPlaybackTime: progress,
                                               } mutableCopy];
      if (self.currentCoverImage) {
         MPMediaItemArtwork *artwork = [[MPMediaItemArtwork alloc]initWithImage:self.currentCoverImage];
         nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork;
      }
      
      [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nowPlayingInfo;
   }
}

@end
