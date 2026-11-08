package org.fossify.gallery.saf

import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.hasExternalSDCard
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.otgPath
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.extensions.toast
import org.fossify.gallery.R

class SafStoragePickerDialog(
    private val activity: BaseSimpleActivity,
    private val locationStore: SafLocationStore,
    private val onLocalStorageSelected: (String) -> Unit,
    private val onOtherLocations: () -> Unit,
    private val onSafLocationSelected: (SafLocation) -> Unit
) {
    private sealed interface Option {
        data class LocalStorage(val path: String, val label: String) : Option
        data object OtherLocations : Option
        data object PreviouslyAdded : Option
    }

    fun show() {
        val options = buildList {
            add(Option.LocalStorage(activity.internalStoragePath, activity.getString(org.fossify.commons.R.string.internal)))
            if (activity.hasExternalSDCard()) {
                add(Option.LocalStorage(activity.sdCardPath, activity.getString(org.fossify.commons.R.string.sd_card)))
            } else if (activity.hasOTGConnected()) {
                add(Option.LocalStorage(activity.otgPath, activity.getString(org.fossify.commons.R.string.usb)))
            }
            add(Option.OtherLocations)
            add(Option.PreviouslyAdded)
        }

        val labels = options.map { option ->
            when (option) {
                is Option.LocalStorage -> option.label
                Option.OtherLocations -> activity.getString(R.string.other_locations)
                Option.PreviouslyAdded -> activity.getString(R.string.previously_added_locations)
            }
        }.toTypedArray()

        activity.getAlertDialogBuilder()
            .setTitle(org.fossify.commons.R.string.select_storage)
            .setItems(labels) { _, index ->
                when (val option = options[index]) {
                    is Option.LocalStorage -> onLocalStorageSelected(option.path)
                    Option.OtherLocations -> onOtherLocations()
                    Option.PreviouslyAdded -> showPreviouslyAddedLocations()
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun showPreviouslyAddedLocations() {
        val locations = locationStore.getLocations()
        if (locations.isEmpty()) {
            activity.toast(R.string.no_previously_added_locations)
            return
        }

        val labels = locations.map { location ->
            when {
                !location.hasReadAccess -> "${location.displayName} — ${activity.getString(R.string.saf_unavailable)}"
                !location.hasWriteAccess -> "${location.displayName} — ${activity.getString(R.string.saf_read_only)}"
                else -> location.displayName
            }
        }.toTypedArray()

        var selectedIndex = -1
        activity.getAlertDialogBuilder()
            .setTitle(R.string.previously_added_locations)
            .setSingleChoiceItems(labels, selectedIndex) { _, index ->
                selectedIndex = index
            }
            .setPositiveButton(R.string.saf_use_location) { _, _ ->
                val location = locations.getOrNull(selectedIndex)
                if (location == null) {
                    activity.toast(R.string.no_previously_added_locations)
                } else if (location.hasReadAccess) {
                    onSafLocationSelected(location)
                } else {
                    activity.toast(R.string.saf_location_unavailable)
                }
            }
            .setNeutralButton(R.string.saf_remove_location) { _, _ ->
                val location = locations.getOrNull(selectedIndex)
                if (location == null) {
                    activity.toast(R.string.no_previously_added_locations)
                } else {
                    locationStore.remove(location.treeUri)
                    activity.toast(R.string.saf_location_removed)
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }
}
