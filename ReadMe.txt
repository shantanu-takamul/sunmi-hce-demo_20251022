Test Steps：
 (1)Flash the rom veriosn to P3H_user_3.0.2T3_51_20241225 on P3H device
 (2)Install SunmiHCEDemo_v1.0.3_debug.apk on P3H(POS)
 (3)Install SunmiNFCDemo_v1.0.2_debug.apk on an Android phone which with NFC function
 
 1.Test POS write data, Android phone read data:
  (1) OPen SunmiHCEDemo on the POS -> select NFC type2 or NFC type4 -> open HCE
  (2) POS select nfc data1/data2/data3 -> Write NDEF data
  (3) OPen SunmiNFCDemo on Android phone -> click button [Test HCE Read NdefMessage] -> tap phone to POS -> Read POS written data at step (1)
  (4) POS click button [HCE close]
 
 2.Test Android phone write data, POS read data:
  (1) Open SunmiHCEDemo on POS -> select NFC type2 or NFC type4 -> open HCE
  (2) OPen SunmiNFCDemo on Android phone -> click button [Test HCE Write NdefMessage] -> select nfc data1/data2/data3 by NFC type -> tap phone to POS and write
  (3) POS click [Read NDEF data] -> read Android phone written data at step (2)
  (4) POS click button [HCE close]


Code Description：
 1.For the source code of SunmiHCEDemo_v1.0.1_debug.apk, please refer to [MainActivity.java] in [sunmihcedemo.rar]
 2.For the source code of SunmiNFCDemo_v1.0.0_debug.apk, please refer to [TestHCEReadActivity.java],[TestHCEWriteActivity.java] in[SunmiNFCDemo.rar]


Note:
 1.Currently, only P3,P3H,P3KH device support HCE fuction.
 2.Video of Sunm HCE: https://alidocs.dingtalk.com/i/nodes/7NkDwLng8ZMnqodetm9kbRLLJKMEvZBY?utm_scene=person_space&iframeQuery=anchorId%3Duu_m5c7yju0xfhtjxmpcz 
  

