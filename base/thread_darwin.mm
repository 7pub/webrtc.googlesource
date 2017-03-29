/*
 *  Copyright 2017 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

#include "webrtc/base/thread.h"

#import <Foundation/Foundation.h>

#include "webrtc/base/platform_thread.h"

/*
 * This file contains platform-specific implementations for several
 * methods in rtc::Thread.
 */

namespace {
void InitCocoaMultiThreading() {
  if ([NSThread isMultiThreaded] == NO) {
    // The sole purpose of this autorelease pool is to avoid a console
    // message on Leopard that tells us we're autoreleasing the thread
    // with no autorelease pool in place.
    @autoreleasepool {
      [NSThread detachNewThreadSelector:@selector(class)
                               toTarget:[NSObject class]
                             withObject:nil];
    }
  }

  RTC_DCHECK([NSThread isMultiThreaded]);
}
}

namespace rtc {

ThreadManager::ThreadManager() {
  pthread_key_create(&key_, nullptr);
#ifndef NO_MAIN_THREAD_WRAPPING
  WrapCurrentThread();
#endif
  // This is necessary to alert the cocoa runtime of the fact that
  // we are running in a multithreaded environment.
  InitCocoaMultiThreading();
}

ThreadManager::~ThreadManager() {
  @autoreleasepool {
    UnwrapCurrentThread();
    pthread_key_delete(key_);
  }
}

// static
void* Thread::PreRun(void* pv) {
  ThreadInit* init = static_cast<ThreadInit*>(pv);
  ThreadManager::Instance()->SetCurrentThread(init->thread);
  rtc::SetCurrentThreadName(init->thread->name_.c_str());
  @autoreleasepool {
    if (init->runnable) {
      init->runnable->Run(init->thread);
    } else {
      init->thread->Run();
    }
  }
  delete init;
  return nullptr;
}

bool Thread::ProcessMessages(int cmsLoop) {
  int64_t msEnd = (kForever == cmsLoop) ? 0 : TimeAfter(cmsLoop);
  int cmsNext = cmsLoop;

  while (true) {
    @autoreleasepool {
      Message msg;
      if (!Get(&msg, cmsNext))
        return !IsQuitting();
      Dispatch(&msg);

      if (cmsLoop != kForever) {
        cmsNext = static_cast<int>(TimeUntil(msEnd));
        if (cmsNext < 0)
          return true;
      }
    }
  }
}
}  // namespace rtc
