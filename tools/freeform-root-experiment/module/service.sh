#!/system/bin/sh
# Runs late in boot as root (Magisk service.sh). Make the freeform dev switches
# persistent; the feature XML itself is bind-mounted from this module's system/.
settings put global enable_freeform_support 1
settings put global force_resizable_activities 1
