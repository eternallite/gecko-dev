/* vim: set ft=javascript ts=2 et sw=2 tw=80: */
/* Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */
"use strict";

/**
 * Test that Security details tab contains the expected data.
 */

add_task(function* () {
  let [tab, debuggee, monitor] = yield initNetMonitor(CUSTOM_GET_URL);
  let { $, EVENTS, NetMonitorView } = monitor.panelWin;
  let { RequestsMenu, NetworkDetails } = NetMonitorView;
  RequestsMenu.lazyUpdate = false;

  info("Performing a secure request.");
  debuggee.performRequests(1, "https://example.com" + CORS_SJS_PATH);

  yield waitForNetworkEvents(monitor, 1);

  info("Selecting the request.");
  RequestsMenu.selectedIndex = 0;

  info("Waiting for details pane to be updated.");
  yield monitor.panelWin.once(EVENTS.TAB_UPDATED);

  info("Selecting security tab.");
  NetworkDetails.widget.selectedIndex = 5;

  info("Waiting for security tab to be updated.");
  yield monitor.panelWin.once(EVENTS.TAB_UPDATED);

  let errorbox = $("#security-error");
  let infobox = $("#security-information");

  is(errorbox.hidden, true, "Error box is hidden.");
  is(infobox.hidden, false, "Information box visible.");

  // Connection
  checkLabel("#security-protocol-version-value", "TLSv1");
  checkLabel("#security-ciphersuite-value", "TLS_RSA_WITH_AES_128_CBC_SHA");

  // Host
  checkLabel("#security-info-host-header", "Host example.com:");
  checkLabel("#security-http-strict-transport-security-value", "Disabled");
  checkLabel("#security-public-key-pinning-value", "Disabled");

  // Cert
  checkLabel("#security-cert-subject-cn", "example.com");
  checkLabel("#security-cert-subject-o", "<Not Available>");
  checkLabel("#security-cert-subject-ou", "<Not Available>");

  checkLabel("#security-cert-issuer-cn", "Temporary Certificate Authority");
  checkLabel("#security-cert-issuer-o", "Mozilla Testing");
  checkLabel("#security-cert-issuer-ou", "<Not Available>");

  // Locale sensitive and varies between timezones. Cant't compare equality or
  // the test fails depending on which part of the world the test is executed.
  checkLabelNotEmpty("#security-cert-validity-begins");
  checkLabelNotEmpty("#security-cert-validity-expires");

  checkLabelNotEmpty("#security-cert-sha1-fingerprint");
  checkLabelNotEmpty("#security-cert-sha256-fingerprint");
  yield teardown(monitor);

  /**
   * A helper that compares value attribute of a label with given selector to the
   * expected value.
   */
  function checkLabel(selector, expected) {
    info("Checking label " + selector);

    let element = $(selector);

    ok(element, "Selector matched an element.");
    is(element.value, expected, "Label has the expected value.");
  }

  /**
   * A helper that checks the label with given selector is not an empty string.
   */
  function checkLabelNotEmpty(selector) {
    info("Checking that label " + selector + " is non-empty.");

    let element = $(selector);

    ok(element, "Selector matched an element.");
    isnot(element.value, "", "Label was not empty.");
  }
});
