package com.cookie.mobile

import platform.UIKit.UIDevice

actual fun platformName(): String = UIDevice.currentDevice.systemName
