package edu.cmu.cs.sinfonia;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.UUID;

public class Backend implements Parcelable {
    public String name;
    public String resource;
    public String version;
    public UUID uuid;
    public String description;
    public GeoLocation geoLocation;

    public Backend() {}

    protected Backend(Parcel in) {
        description = in.readString();
    }

    public Backend(String name, String resource, UUID uuid, String version) {
        this.name = name;
        this.resource = resource;
        this.uuid = uuid;
        this.version = version;
    }

    public Backend(String name, String resource, String uuid, String version) {
        this.name = name;
        this.resource = resource;
        this.uuid = UUID.fromString(uuid);
        this.version = version;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setGeoLocation(double latitude, double longitude) {
        this.geoLocation = new GeoLocation(latitude, longitude);
    }

    public static class GeoLocation {
        public double latitude;
        public double longitude;

        public GeoLocation(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public double distance(GeoLocation other) {
            double earthRadius = 6371.0;

            double lat1Rad = Math.toRadians(latitude);
            double lon1Rad = Math.toRadians(longitude);
            double lat2Rad = Math.toRadians(other.latitude);
            double lon2Rad = Math.toRadians(other.longitude);

            double dLat = lat2Rad - lat1Rad;
            double dLon = lon2Rad - lon1Rad;

            double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(lat1Rad) * Math.cos(lat2Rad) * Math.pow(Math.sin(dLon / 2), 2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

            return earthRadius * c; // Distance in kilometers
        }

        @NonNull
        @Override
        public String toString() {
            return "GeoLocation(" + latitude + ", " + longitude + ")";
        }
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(description);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Backend> CREATOR = new Creator<Backend>() {
        @Override
        public Backend createFromParcel(Parcel in) {
            return new Backend(in);
        }

        @Override
        public Backend[] newArray(int size) {
            return new Backend[size];
        }
    };
}