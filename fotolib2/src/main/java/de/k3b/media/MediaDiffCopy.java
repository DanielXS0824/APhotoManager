/*
 * Copyright (c) 2017 by k3b.
 *
 * This file is part of AndroFotoFinder / #APhotoManager.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>
 */

package de.k3b.media;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;

import de.k3b.io.ListUtils;
import de.k3b.media.MediaUtil.FieldID;

import de.k3b.tagDB.TagProcessor;
import de.k3b.tagDB.TagRepository;

/**
 * Copy delta between two {@link IMetaApi} items.
 * Used for multiselection Exif update.
 *
 * Created by k3b on 07.07.2017.
 */

public class MediaDiffCopy {
    private int numberOfChangedFields = 0;

    /** where modification data comes from */
    private IMetaApi newData = null;

    /** the fields that have to be copied by MediaUtil excluding special MediaDiffCopy processing */
    private EnumSet<FieldID> diffSet = null;

    /** time units to be added to destination time */
    private long timeAdded = 0;

    /** if not empty: these tags will be added/removed */
    private final List<String> addedTags = new ArrayList<String>();
    private final List<String> removedTags = new ArrayList<String>();

    /** source title/description starting with this: Instead of copy this will be appended to destination */
    private static final String APPEND_PREFIX = "+";

    /** if not null: this will be added to title/description */
    private String titleAppend = null;
    private String descriptionAppend = null;

    /** resets to: no special processing required.  */
    public void close() {
        this.diffSet = null;
        this.timeAdded = 0;
        this.numberOfChangedFields = 0;
        this.addedTags.clear();
        this.removedTags.clear();
        this.titleAppend = null;
        this.descriptionAppend = null;
        this.newData = null;
    }

    /** Initialisation to define the difference. return null if there is no diff between them */
    public MediaDiffCopy setDiff(IMetaApi newData, FieldID fieldId, FieldID... fieldIds) {
        return setDiff(newData, EnumSet.of(fieldId, fieldIds));
    }

    /** Initialisation to define the difference. return null if there is no diff between them */
    public MediaDiffCopy setDiff(IMetaApi newData, EnumSet<FieldID> diffSet) {
        close();
        this.diffSet = diffSet;
        this.diffSet.remove(FieldID.path);
        this.numberOfChangedFields = this.diffSet.size();

        if (this.numberOfChangedFields > 0) {
            this.newData = newData;
            return this;
        }
        return null;
    }

    public void fixTagRepository() {
        if ((this.addedTags != null) && (this.addedTags.size() > 0)) {
            TagRepository tagRepository = TagRepository.getInstance();
            tagRepository.includeTagNamesIfNotFound(this.addedTags);
            tagRepository.save();
        }
    }

    /** Initialisation to define the difference. return null if there is no diff between them */
    public MediaDiffCopy setDiff(IMetaApi initialData, IMetaApi newData) {
        close();
        this.diffSet = MediaUtil.getChangesAsDiffsetOrNull(initialData, newData);

        if (this.diffSet != null) {
            // in gui data was changed
            this.diffSet.remove(FieldID.path);

            this.numberOfChangedFields = this.diffSet.size();

            if (this.diffSet.contains(FieldID.dateTimeTaken)) {
                Date currentDateTimeTaken = newData.getDateTimeTaken();
                Date initialDateTimeTaken = initialData.getDateTimeTaken();

                if ((initialDateTimeTaken != null) && (currentDateTimeTaken != null)) {
                    this.timeAdded = currentDateTimeTaken.getTime() - initialDateTimeTaken.getTime();
                    this.diffSet.remove(FieldID.dateTimeTaken);
                }
            }

            if (this.diffSet.contains(FieldID.tags)) {
                TagProcessor.getDiff(initialData.getTags(), newData.getTags(), this.addedTags, this.removedTags);
                this.diffSet.remove(FieldID.tags);
            }

            if (this.diffSet.contains(FieldID.title)) {
                String value = newData.getTitle();
                if ((value != null) && (value.startsWith(APPEND_PREFIX)) && (value.length() > APPEND_PREFIX.length())) {
                    this.titleAppend = value.substring(APPEND_PREFIX.length());
                    this.diffSet.remove(FieldID.title);
                }
            }

            if (this.diffSet.contains(FieldID.description)) {
                String value = newData.getDescription();
                if ((value != null) && (value.startsWith(APPEND_PREFIX)) && (value.length() > APPEND_PREFIX.length())) {
                    this.descriptionAppend = value.substring(APPEND_PREFIX.length());
                    this.diffSet.remove(FieldID.description);
                }
            }
        }

        if (this.numberOfChangedFields > 0) {
            this.newData = newData;
            return this;
        }
        close();
        return null;
    }

    /** Similar to {@link MediaUtil#copySpecificProperties(IMetaApi, IMetaApi, EnumSet)} but with special diff handling. */
    public List<FieldID> applyChanges(IMetaApi destination) {
        if (this.numberOfChangedFields > 0) {
            // note: special processing was excluded from this.diffSet
            List<FieldID> collectedChanges = MediaUtil.copySpecificProperties(destination, newData, this.diffSet);

            if (this.timeAdded != 0) {
                Date oldDate = destination.getDateTimeTaken();

                if (oldDate != null) {
                    destination.setDateTimeTaken(new Date(
                            oldDate.getTime() + this.timeAdded));
                } else {
                    destination.setDateTimeTaken(this.newData.getDateTimeTaken());
                }
                collectedChanges.add(FieldID.dateTimeTaken);
            }

            if ((this.addedTags.size() > 0) || (this.removedTags.size() > 0)) {
                List<String> updated = TagProcessor.getUpdated(destination.getTags(), this.addedTags, this.removedTags);
                if (updated != null) {
                    destination.setTags(updated);
                    collectedChanges.add(FieldID.tags);
                }
            }

            String modifiedValue = getAppended(destination.getTitle(), this.titleAppend);
            if (modifiedValue != null) {
                destination.setTitle(modifiedValue);
                collectedChanges.add(FieldID.title);
            }

            modifiedValue = getAppended(destination.getDescription(), this.descriptionAppend);
            if (modifiedValue != null) {
                destination.setDescription(modifiedValue);
                collectedChanges.add(FieldID.description);
            }

            if (collectedChanges.size() > 0)  return collectedChanges;
        }
        return null;
    }

    /** method to append text to title or description */
    private String getAppended(String oldValue, String append) {
        if (append != null) {
            String value = oldValue;
            if (value == null) {
                value = append.trim();
            } else if (value.indexOf(append.trim()) < 0) {
                // not already included
                value += append;
            } else {
                // already included: no change
                value = null;
            }
            return value;
        }
        return null;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append(this.getClass().getSimpleName()).append(":");
        if (this.numberOfChangedFields > 0) {
            result.append(MediaUtil.toString(this.newData,true, EnumSet.complementOf(this.diffSet)));

            if (this.titleAppend != null) result.append(" title+=").append(this.titleAppend);
            if (this.descriptionAppend != null) result.append(" description+=").append(this.descriptionAppend);

            if (this.timeAdded != 0) result.append(" date+=").append(toString(this.timeAdded, 1000, 60, 60, 24, 30, 12 ,365));

            if (this.removedTags.size() > 0) result.append(" tags-=").append(ListUtils.toString(this.removedTags));
            if (this.addedTags.size() > 0) result.append(" tags+=").append(ListUtils.toString(this.addedTags));
        }
        return result.toString();
    }

    /** inacurate converseion to y m d h m s */
    private String toString(long timeAdded, int... divisors) {
        long remaining = timeAdded;
        StringBuilder result = new StringBuilder();
        for (int divisor : divisors) {
            result.insert(0, " ");
            result.insert(0, remaining % divisor);
            remaining /= divisor;
        }
        return result.toString();
    }
}
