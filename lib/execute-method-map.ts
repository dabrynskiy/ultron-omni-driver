import type {ExecuteMethodMap} from '@appium/types';
import {AndroidDriver} from 'appium-android-driver';

export const executeMethodMap = {
  ...AndroidDriver.executeMethodMap,

  'mobile: ultronSnapshot': {
    command: 'mobileUltronSnapshot',
    params: {optional: ['maxDepth', 'maxNodes', 'useUnmergedTree', 'mode']},
  },
  'mobile: ultronAct': {
    command: 'mobileUltronAct',
    params: {
      required: ['matcher', 'action'],
      optional: ['text', 'timeoutMs', 'offsetX', 'offsetY', 'keyCode', 'metaState', 'flags', 'autoScroll', 'name'],
    },
  },
  'mobile: ultronAssert': {
    command: 'mobileUltronAssert',
    params: {required: ['matcher'], optional: ['condition', 'exists', 'timeoutMs']},
  },
  'mobile: ultronComposeAct': {
    command: 'mobileUltronComposeAct',
    params: {
      required: ['matcher', 'action'],
      optional: ['text', 'itemMatcher', 'options', 'useUnmergedTree', 'timeoutMs', 'name'],
    },
  },
  'mobile: ultronComposeAssert': {
    command: 'mobileUltronComposeAssert',
    params: {
      required: ['matcher'],
      optional: ['assertion', 'text', 'expected', 'condition', 'options', 'useUnmergedTree', 'timeoutMs', 'name'],
    },
  },
  'mobile: ultronAutomatorAct': {
    command: 'mobileUltronAutomatorAct',
    params: {required: ['strategy', 'locator', 'action'], optional: ['text', 'packageName', 'index', 'timeoutMs']},
  },
  'mobile: ultronAutomatorAssert': {
    command: 'mobileUltronAutomatorAssert',
    params: {
      required: ['strategy', 'locator'],
      optional: ['assertion', 'text', 'expected', 'packageName', 'index', 'timeoutMs'],
    },
  },
  'mobile: ultronWebAct': {
    command: 'mobileUltronWebAct',
    params: {required: ['webViewMatcher', 'locator', 'action'], optional: ['text', 'frames', 'timeoutMs']},
  },
  'mobile: ultronWebAssert': {
    command: 'mobileUltronWebAssert',
    params: {required: ['webViewMatcher', 'locator'], optional: ['assertion', 'text', 'frames', 'timeoutMs']},
  },
  'mobile: ultronLastResult': {command: 'mobileUltronLastResult'},
  'mobile: ultronBatch': {
    command: 'mobileUltronBatch',
    params: {required: ['steps'], optional: ['schemaVersion']},
  },
  'mobile: ultronBack': {command: 'mobileUltronBack'},
  'mobile: ultronHideKeyboard': {command: 'mobileUltronHideKeyboard'},
  'mobile: ultronElement': {
    command: 'mobileUltronElement',
    params: {required: ['backend', 'using', 'value', 'action'], optional: ['text', 'requireUnique']},
  },
  'mobile: ultronRecycler': {
    command: 'mobileUltronRecycler',
    params: {
      required: ['matcher', 'action'],
      optional: [
        'position',
        'itemMatcher',
        'childMatcher',
        'condition',
        'itemCount',
        'searchLimit',
        'text',
        'timeoutMs',
        'name',
      ],
    },
  },

  'mobile: swipe': {
    command: 'mobileSwipe',
    params: {
      required: ['elementId'],
      optional: ['direction', 'swiper', 'startCoordinates', 'endCoordinates', 'precisionDescriber'],
    },
  },
  'mobile: scrollToPage': {
    command: 'mobileScrollToPage',
    params: {
      required: ['elementId'],
      optional: ['scrollTo', 'scrollToPage', 'smoothScroll'],
    },
  },
  'mobile: navigateTo': {
    command: 'mobileNavigateTo',
    params: {
      required: ['elementId', 'menuItemId'],
    },
  },
  'mobile: clickAction': {
    command: 'mobileClickAction',
    params: {
      required: ['elementId'],
      optional: ['tapper', 'coordinatesProvider', 'precisionDescriber', 'inputDevice', 'buttonState'],
    },
  },

  'mobile: deviceInfo': {
    command: 'mobileGetDeviceInfo',
  },

  'mobile: isToastVisible': {
    command: 'mobileIsToastVisible',
    params: {
      required: ['text'],
      optional: ['isRegexp'],
    },
  },

  'mobile: openDrawer': {
    command: 'mobileOpenDrawer',
    params: {
      required: ['elementId'],
      optional: ['gravity'],
    },
  },
  'mobile: closeDrawer': {
    command: 'mobileCloseDrawer',
    params: {
      required: ['elementId'],
      optional: ['gravity'],
    },
  },

  'mobile: setDate': {
    command: 'mobileSetDate',
    params: {
      required: ['elementId', 'year', 'monthOfYear', 'dayOfMonth'],
    },
  },
  'mobile: setTime': {
    command: 'mobileSetTime',
    params: {
      required: ['elementId', 'hours', 'minutes'],
    },
  },

  'mobile: backdoor': {
    command: 'mobileBackdoor',
    params: {
      required: ['target', 'methods'],
      optional: ['elementId'],
    },
  },

  'mobile: flashElement': {
    command: 'mobileFlashElement',
    params: {
      required: ['elementId'],
      optional: ['durationMillis', 'repeatCount'],
    },
  },

  'mobile: uiautomator': {
    command: 'mobileUiautomator',
    params: {
      required: ['strategy', 'locator', 'action'],
      optional: ['index'],
    },
  },
  'mobile: uiautomatorPageSource': {
    command: 'mobileUiautomatorPageSource',
  },

  'mobile: webAtoms': {
    command: 'mobileWebAtoms',
    params: {
      required: ['webviewEl', 'forceJavascriptEnabled', 'methodChain'],
    },
  },

  'mobile: dismissAutofill': {
    command: 'mobileDismissAutofill',
    params: {
      required: ['elementId'],
    },
  },

  'mobile: registerIdlingResources': {
    command: 'mobileRegisterIdlingResources',
    params: {
      required: ['classNames'],
    },
  },
  'mobile: unregisterIdlingResources': {
    command: 'mobileUnregisterIdlingResources',
    params: {
      required: ['classNames'],
    },
  },
  'mobile: listIdlingResources': {
    command: 'mobileListIdlingResources',
  },
  'mobile: waitForUIThread': {
    command: 'mobileWaitForUIThread',
  },

  'mobile: pressKey': {
    command: 'mobilePressKey',
    params: {
      required: ['keycode'],
      optional: ['metastate', 'flags', 'isLongPress'],
    },
  },

  'mobile: setClipboard': {
    command: 'mobileSetClipboard',
    params: {
      required: ['content'],
      optional: ['contentType', 'label'],
    },
  },
  'mobile: getClipboard': {
    command: 'mobileGetClipboard',
  },

  'mobile: startService': {
    command: 'mobileStartService',
    params: {
      required: ['intent'],
      optional: ['user', 'foreground'],
    },
  },
  'mobile: stopService': {
    command: 'mobileStopService',
    params: {
      required: ['intent'],
      optional: ['user'],
    },
  },

  'mobile: startActivity': {
    command: 'mobileStartActivity',
    params: {
      required: ['appActivity'],
      optional: ['locale', 'optionalIntentArguments', 'optionalActivityArguments'],
    },
  },

  'mobile: screenshots': {
    command: 'mobileScreenshots',
    params: {
      optional: ['displayId'],
    },
  },
} as const satisfies ExecuteMethodMap<any>;
