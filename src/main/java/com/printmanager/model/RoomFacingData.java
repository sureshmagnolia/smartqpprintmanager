package com.printmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RoomFacingData {
    private String collegeName = "";
    private String examName = "";
    private String hallNumber = "";
    private String roomName = "";
    private String location = "";
    private String invigilatorName = "";
    private String stream = "";
    private String date = "";
    private String time = "";
    private int totalStudents = 0;
    private int totalBookletsExclScribes = 0;
    private List<String> uniqueQPCodes = new ArrayList<>();
    private List<CourseSummaryEntry> courseSummary = new ArrayList<>();
    private List<StudentEntry> students = new ArrayList<>();

    public RoomFacingData() {}

    public String getCollegeName() { return collegeName != null ? collegeName : ""; }
    public void setCollegeName(String collegeName) { this.collegeName = collegeName; }

    public String getExamName() { return examName != null ? examName : ""; }
    public void setExamName(String examName) { this.examName = examName; }

    public String getHallNumber() { return hallNumber != null ? hallNumber : ""; }
    public void setHallNumber(String hallNumber) { this.hallNumber = hallNumber; }

    public String getRoomName() { return roomName != null ? roomName : ""; }
    public void setRoomName(String roomName) { this.roomName = roomName; }

    public String getLocation() { return location != null ? location : ""; }
    public void setLocation(String location) { this.location = location; }

    public String getInvigilatorName() { return invigilatorName != null ? invigilatorName : ""; }
    public void setInvigilatorName(String invigilatorName) { this.invigilatorName = invigilatorName; }

    public String getStream() { return stream != null ? stream : ""; }
    public void setStream(String stream) { this.stream = stream; }

    public String getDate() { return date != null ? date : ""; }
    public void setDate(String date) { this.date = date; }

    public String getTime() { return time != null ? time : ""; }
    public void setTime(String time) { this.time = time; }

    public int getTotalStudents() { return totalStudents; }
    public void setTotalStudents(int totalStudents) { this.totalStudents = totalStudents; }

    public int getTotalBookletsExclScribes() { return totalBookletsExclScribes; }
    public void setTotalBookletsExclScribes(int totalBookletsExclScribes) { this.totalBookletsExclScribes = totalBookletsExclScribes; }

    public List<String> getUniqueQPCodes() { return uniqueQPCodes; }
    public void setUniqueQPCodes(List<String> uniqueQPCodes) { this.uniqueQPCodes = uniqueQPCodes != null ? uniqueQPCodes : new ArrayList<>(); }

    public List<CourseSummaryEntry> getCourseSummary() { return courseSummary; }
    public void setCourseSummary(List<CourseSummaryEntry> courseSummary) { this.courseSummary = courseSummary != null ? courseSummary : new ArrayList<>(); }

    public List<StudentEntry> getStudents() { return students; }
    public void setStudents(List<StudentEntry> students) { this.students = students != null ? students : new ArrayList<>(); }

    public boolean hasStudents() {
        return students != null && !students.isEmpty();
    }

    public int getAssignedStudentsCount() {
        int count = getTotalBookletsExclScribes();
        if (count > 0) return count;
        if (students != null && !students.isEmpty()) {
            long nonScribes = students.stream().filter(s -> !s.isScribe()).count();
            if (nonScribes > 0) return (int) nonScribes;
        }
        if (courseSummary != null && !courseSummary.isEmpty()) {
            int sum = courseSummary.stream().mapToInt(CourseSummaryEntry::getCount).sum();
            if (sum > 0) return sum;
        }
        return getTotalStudents();
    }

    public boolean hasScribes() {
        if (students != null) {
            for (StudentEntry s : students) {
                if (s.isScribe()) return true;
            }
        }
        if (courseSummary != null) {
            for (CourseSummaryEntry c : courseSummary) {
                if (c.getScribeCount() > 0) return true;
            }
        }
        return false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StudentEntry {
        private int seatNumber;
        private String regNo = "";
        private String name = "";
        private String courseName = "";
        private String qpCode = "";
        private boolean isScribe;
        private String remarks = "";

        public StudentEntry() {}

        public int getSeatNumber() { return seatNumber; }
        public void setSeatNumber(int seatNumber) { this.seatNumber = seatNumber; }

        public String getRegNo() { return regNo != null ? regNo : ""; }
        public void setRegNo(String regNo) { this.regNo = regNo; }

        public String getName() { return name != null ? name : ""; }
        public void setName(String name) { this.name = name; }

        public String getCourseName() { return courseName != null ? courseName : ""; }
        public void setCourseName(String courseName) { this.courseName = courseName; }

        public String getQpCode() { return qpCode != null ? qpCode : ""; }
        public void setQpCode(String qpCode) { this.qpCode = qpCode; }

        @JsonProperty("isScribe")
        public boolean isScribe() { return isScribe; }
        @JsonProperty("isScribe")
        public void setScribe(boolean scribe) { isScribe = scribe; }

        public String getRemarks() { return remarks != null ? remarks : ""; }
        public void setRemarks(String remarks) { this.remarks = remarks; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CourseSummaryEntry {
        private String qpCode = "";
        private String courseName = "";
        private String smartCourseName = "";
        private int count;
        private int totalCount;
        private int scribeCount;

        public CourseSummaryEntry() {}

        public String getQpCode() { return qpCode != null ? qpCode : ""; }
        public void setQpCode(String qpCode) { this.qpCode = qpCode; }

        public String getCourseName() { return courseName != null ? courseName : ""; }
        public void setCourseName(String courseName) { this.courseName = courseName; }

        public String getSmartCourseName() { return smartCourseName != null ? smartCourseName : ""; }
        public void setSmartCourseName(String smartCourseName) { this.smartCourseName = smartCourseName; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public int getTotalCount() { return totalCount; }
        public void setTotalCount(int totalCount) { this.totalCount = totalCount; }

        public int getScribeCount() { return scribeCount; }
        public void setScribeCount(int scribeCount) { this.scribeCount = scribeCount; }
    }
}

