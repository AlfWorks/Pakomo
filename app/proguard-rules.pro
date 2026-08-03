# Pakomo does not use reflection-based serialization in the initial program slice.
-keep class hev.htproxy.TProxyService { *; }

# Public component name is part of the external automation protocol.
-keep public class com.pakomo.automation.ControlReceiver { public <init>(); }
