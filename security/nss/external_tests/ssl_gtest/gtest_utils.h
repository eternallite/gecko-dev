/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set ts=2 et sw=2 tw=80: */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef gtest_utils_h__
#define gtest_utils_h__

namespace nss_test {

// Gtest utilities
class Timeout : public PollTarget {
 public:
  Timeout(int32_t timer_ms) : handle_(nullptr), timed_out_(false) {
    Poller::Instance()->SetTimer(timer_ms, this, &Timeout::ExpiredCallback,
                                 &handle_);
  }

  static void ExpiredCallback(PollTarget* target, Event event) {
    Timeout* timeout = static_cast<Timeout*>(target);
    timeout->timed_out_ = true;
  }

  void Cancel() { handle_->Cancel(); }

  bool timed_out() const { return timed_out_; }

 private:
  Poller::Timer* handle_;
  bool timed_out_;
};

}  // namespace nss_test

#define WAIT_(expression, timeout) \
  do {                             \
    Timeout tm(timeout);           \
    while (!(expression)) {        \
      Poller::Instance()->Poll();  \
      if (tm.timed_out()) break;   \
    }                              \
  } while (0)

#define ASSERT_TRUE_WAIT(expression, timeout) \
  do {                                        \
    WAIT_(expression, timeout);               \
    ASSERT_TRUE(expression);                  \
  } while (0)

#endif
