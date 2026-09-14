# FundamentalIntelligence

FundamentalOS on-device intelligence services ("ASI Alt"), progressively
replacing ASI (com.google.android.as).

It currently provides the device `SmartspaceService`, taking over from ASI as
the smartspace target source. Phase 1 serves lockscreen/AOD weather cards by
binding the FundamentalOS Weather app (`org.fundamentalos.weather`) over an
AIDL interface. Additional smartspace cards and, over time, other on-device
intelligence roles will be added here.
