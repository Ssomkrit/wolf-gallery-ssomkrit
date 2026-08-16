package dev.encgallery.crypto

class VaultLockedException :
    IllegalStateException("VaultDataKey not primed — vault must be unlocked first")
