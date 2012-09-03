/*
 * Copyright (C) 2011 Sony Ericsson Mobile Communications AB
 *
 * This file is part of ChkBugReport.
 *
 * ChkBugReport is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * ChkBugReport is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ChkBugReport.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.sonyericsson.chkbugreport.plugins.logs;

import com.sonyericsson.chkbugreport.BugReportModule;
import com.sonyericsson.chkbugreport.Module;
import com.sonyericsson.chkbugreport.ProcessRecord;
import com.sonyericsson.chkbugreport.Section;
import com.sonyericsson.chkbugreport.Util;
import com.sonyericsson.chkbugreport.doc.Block;
import com.sonyericsson.chkbugreport.doc.Bug;
import com.sonyericsson.chkbugreport.doc.Chapter;
import com.sonyericsson.chkbugreport.doc.DocNode;
import com.sonyericsson.chkbugreport.doc.Link;
import com.sonyericsson.chkbugreport.doc.Para;
import com.sonyericsson.chkbugreport.doc.ProcessLink;
import com.sonyericsson.chkbugreport.doc.Table;

public class SystemLogPlugin extends LogPlugin {

    private Section mKernelLog;

    public SystemLogPlugin() {
        super("System", "system", Section.SYSTEM_LOG);
    }

    protected SystemLogPlugin(String which, String id, String sectionName) {
        super(which, id, sectionName);
    }

    @Override
    public int getPrio() {
        return 30;
    }

    @Override
    public void load(Module br) {
        super.load(br);
    }

    @Override
    protected void generateExtra(BugReportModule br, Chapter ch) {
        generateLogLevelDistribution(br, ch);
    }

    private void generateLogLevelDistribution(BugReportModule br, Chapter mainCh) {
        final String levels = "UFEWIDV";
        final String levelNames[] = {"Unknown", "Fatal", "Error", "Warning", "Info", "Debug", "Verbose"};
        int counts[] = new int[levels.length()];
        int totalLines = getParsedLineCount();
        if (totalLines == 0) return;

        Chapter ch = new Chapter(br, "Log level distribution");
        mainCh.addChapter(ch);

        for (int i = 0; i < totalLines; i++) {
            LogLine sl = getParsedLine(i);
            int idx = Math.max(0, levels.indexOf(sl.level));
            counts[idx]++;
        }

        // Render the data
        Table t = new Table(Table.FLAG_SORT, ch);
        t.addColumn("Level", Table.FLAG_NONE);
        t.addColumn("Nr. of lines", Table.FLAG_ALIGN_RIGHT);
        t.addColumn("% of all log", Table.FLAG_ALIGN_RIGHT);
        t.begin();
        for (int i = 0; i < levels.length(); i++) {
            t.addData(levelNames[i]);
            t.addData(counts[i]);
            t.addData(String.format("%.1f%%", (counts[i] * 100.0f / totalLines)));
        }
        t.end();
    }

    @Override
    protected void analyze(LogLine sl, int i, BugReportModule br, Section s) {
        if (sl.tag.equals("kernel")) {
            if (mKernelLog == null) {
                mKernelLog = new Section(br, Section.KERNEL_LOG_FROM_SYSTEM);
                br.addSection(mKernelLog);
            }
            mKernelLog.addLine(convertToKrnLogLevel(sl.level) + sl.msg);
        }

        if (sl.tag.equals("ActivityManager") && sl.level == 'I') {
            if (sl.msg.startsWith("Start proc ")) {
                analyzeStartProc(sl, br);
            }
            if (sl.msg.startsWith("Displayed ")) {
                analyzeDisplayed(sl, br);
            }
            if (sl.msg.contains("START {act=android.intent.action.MAIN cat=[android.intent.category.HOME]")) {
                analyzeStartHome(sl, br);
            }
            if (sl.msg.startsWith("Config changed: ")) {
                analyzeConfigChanged(sl, br);
            }
        }

        if (sl.tag.equals("AndroidRuntime") && sl.level == 'D') {
            if (sl.msg.startsWith("Calling main entry ")) {
                String procName = sl.msg.substring("Calling main entry ".length());
                ProcessRecord pr = br.getProcessRecord(sl.pid, true, false);
                pr.suggestName(procName, 2);
            }
        }

        if (sl.tag.equals("ActivityManager") && sl.level == 'E') {
            if (sl.msg.startsWith("ANR in ") ||
                    sl.msg.startsWith("Displayed ") ||
                    sl.msg.startsWith("Start proc ") ||
                    sl.msg.startsWith("Load: ") ||
                    sl.msg.startsWith("act=")) {
                analyzeANR(sl, i, br, s);
            }
        }

        if (sl.tag.equals("DEBUG") && sl.level == 'I') {
            if (sl.msg.equals("*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***")) {
                analyzeNativeCrash(sl, i, br, s);
            }
        }

        if (sl.msg.startsWith("hprof: dumping heap strings to ")) {
            analyzeHPROF(sl, i, br, s);
        }

        if (isFatalException(sl)) {
            analyzeFatalException(sl, i, br, s);
        }

        if (sl.level == 'E' && sl.tag.equals("StrictMode")) {
            analyzeStrictMode(sl, i, br, s);
        }

        if (sl.msg.startsWith("GC_CONCURRENT ") ||
                sl.msg.startsWith("GC_EXPLICIT ") ||
                sl.msg.startsWith("GC_HPROF_DUMP_HEAP ") ||
                sl.msg.startsWith("GC_FOR_MALLOC ") ||
                sl.msg.startsWith("GC_EXTERNAL_ALLOC ")) {
            analyzeGC(sl, i, br, s);
        }

        if (sl.tag.equals("WindowManager") && sl.level == 'I') {
            String key = "Setting rotation to ";
            if (sl.msg.startsWith(key)) {
                int rot = sl.msg.charAt(key.length()) - '0';
                analyzeRotation(sl, br, rot);
            }
        }

        if (sl.msg.startsWith("\tat ") && sl.level == 'E') {
            analyzeJavaException(sl, i, br, s);
        }

        // Since any name is better then no-name, suggest a name for each process based on the tag
        ProcessRecord pr = br.getProcessRecord(sl.pid, true, false);
        pr.suggestName("[" + sl.tag + "]", 1); // weakest prio
    }

    private String convertToKrnLogLevel(char level) {
        switch (level) {
            case 'V': return "<7>";
            case 'D': return "<6>";
            case 'I': return "<5>";
            case 'W': return "<4>";
            case 'E': return "<3>";
            case 'F': return "<0>";
        }
        return "<?>";
    }

    private boolean isFatalException(LogLine sl) {
        return sl.msg.startsWith("FATAL EXCEPTION:") || sl.msg.startsWith("*** FATAL EXCEPTION IN SYSTEM PROCESS:");
    }

    private void analyzeConfigChanged(LogLine sl, BugReportModule br) {
        ConfigChange cc = new ConfigChange(sl.ts);
        addConfigChange(cc);
    }

    private void analyzeStartProc(LogLine sl, BugReportModule br) {
        // Extract the process name
        String s = sl.msg.substring("Start proc ".length());
        int idx = s.indexOf(' ');
        if (idx < 0) return;
        String procName = s.substring(0, idx);

        // Extract pid
        idx = s.indexOf(" pid=");
        if (idx < 0) return;
        s = s.substring(idx + 5);
        idx = s.indexOf(' ');
        if (idx < 0) return;
        String pidS = s.substring(0, idx);
        int pid = -1;
        try {
            pid = Integer.parseInt(pidS);
        } catch (NumberFormatException nfe) {
            return;
        }

        // Suggest process name
        ProcessRecord pr = br.getProcessRecord(pid, true, false);
        pr.suggestName(procName, 25);
        pr = br.getProcessRecord(sl.pid, true, false);
        pr.suggestName("system_server", 20);
    }

    private void analyzeNativeCrash(LogLine sl, int i, BugReportModule br, Section s) {
        // Put a marker box
        String anchor = getId() + "log_nc_" + i;
        sl.addMarker("log-float-err", "<a name=\"" + anchor + "\">NATIVE<br/>CRASH</a>", "NATIVE CRASH");

        // Fetch the next log line
        if (i >= s.getLineCount()-2) return;
        i += 2;
        sl = getParsedLine(i);

        // Create a bug and store the relevant log lines
        Bug bug = new Bug(Bug.PRIO_NATIVE_CRASH, sl.ts, "Native crash: " + sl.msg);
        new Block(bug).add(new Link(sl.getAnchor(), "(link to log)"));
        DocNode log = new Block(bug).addStyle("log");
        log.add(sl.copy());
        int end = i + 1;
        while (end < s.getLineCount()) {
            LogLine sl2 = getParsedLine(end);
            if (!sl2.ok) break;
            if (!sl2.tag.equals("DEBUG")) break;
            if (sl2.level != 'I') break;
            log.add(sl2.copy());
            end++;
        }
        bug.setAttr("firstLine", i);
        bug.setAttr("lastLine", end);
        bug.setAttr("section", s);
        br.addBug(bug);
    }

    private void analyzeANR(LogLine sl, int i, BugReportModule br, Section s) {
        // Make sure we are scanning a new ANR
        if (i > 0) {
            LogLine prev = getParsedLine(i - 1);
            if (prev.ok && prev.tag.equals("ActivityManager") && prev.level == 'E') {
                // Ignore this, probably already handled
                return;
            }
        }

        // Put a marker box
        String anchor = getId() + "log_anr_" + i;
        sl.addMarker("log-float-err", "<a name=\"" + anchor + "\">ANR</a>", "ANR");

        // Create a bug and store the relevant log lines
        String msg = sl.msg;
        if (msg.startsWith("Load: ") || msg.startsWith("act=")) {
            msg = "(ANR?) " + msg;
        }
        Bug bug = new Bug(Bug.PRIO_ANR_SYSTEM_LOG, sl.ts, msg);
        new Block(bug).add(new Link(sl.getAnchor(), "(link to log)"));
        DocNode log = new Block(bug).addStyle("log");
        log.add(sl.copy());
        int end = i + 1;
        int cnt = 0;
        while (end < s.getLineCount()) {
            LogLine sl2 = getParsedLine(end);
            if (!sl2.ok) break;
            if (!sl2.tag.equals("ActivityManager")) break;
            if (sl2.level != 'E') break;
            if (sl2.msg.startsWith("100% TOTAL")) {
                if (2 == ++cnt) {
                    log.add(sl2.copy());
                    end++;
                    break;
                }
            }
            log.add(sl2.copy());
            end++;
        }
        bug.setAttr("firstLine", i);
        bug.setAttr("lastLine", end);
        bug.setAttr("section", s);
        br.addBug(bug);
    }

    private void analyzeHPROF(LogLine sl, int i, BugReportModule br, Section s) {
        // Put a marker box
        String anchor = getId() + "log_hprof_" + i;
        sl.addMarker("log-float-err", "<a name=\"" + anchor + "\">HPROF</a>", "HPROF");

        // Create a bug and store the relevant log lines
        Bug bug = new Bug(Bug.PRIO_HPROF, sl.ts, sl.msg);
        bug.setAttr("firstLine", i);
        ProcessRecord pr = br.getProcessRecord(sl.pid, false, false);
        new Block(bug)
            .add("An HPROF dump was saved by process ")
            .add(new ProcessLink(br, sl.pid))
            .add(", you might want to extract it and look at it as well.");
        new Block(bug).add(new Link(sl.getAnchor(), "(link to log)"));
        br.addBug(bug);

        // Also mention this in the process record
        if (pr != null) {
            new Para(pr).add("Heap dump was saved by this process to " + sl.msg.substring(sl.msg.indexOf('"')));
        }
    }

    private void analyzeFatalException(LogLine sl, int i, BugReportModule br, Section s) {
        // Put a marker box
        String anchor = getId() + "log_fe_" + i;
        sl.addMarker("log-float-err", "<a name=\"" + anchor + "\">FATAL<br/>EXCEPTION</a>", "FATAL EXCEPTION");

        // Create a bug and store the relevant log lines
        Bug bug = new Bug(Bug.PRIO_JAVA_CRASH_SYSTEM_LOG, sl.ts, sl.msg);
        new Block(bug).add(new Link(sl.getAnchor(), "(link to log)"));
        DocNode log = new Block(bug).addStyle("log");
        log.add(sl.copy());
        int end = i + 1;
        while (end < s.getLineCount()) {
            LogLine sl2 = getParsedLine(end);
            if (!sl2.ok) break;
            if (!sl2.tag.equals("AndroidRuntime")) break;
            if (sl2.level != 'E') break;
            log.add(sl2.copy());
            end++;
        }
        bug.setAttr("firstLine", i);
        bug.setAttr("lastLine", end);
        bug.setAttr("section", s);
        br.addBug(bug);
    }

    private void analyzeJavaException(LogLine sl, int i, BugReportModule br, Section s) {
        // Find the beginning
        int firstLine = i;
        while (true) {
            int prev = findNextLine(firstLine, -1);
            if (prev < 0) break;
            if (Math.abs(i - prev) > 10) break; // avoid detecting too many lines
            firstLine = prev;
            sl = getParsedLine(firstLine);
            if (sl.msg.startsWith("\tat ") || isFatalException(sl)) {
                return; // avoid finding the same exception many times
            }
        }

        // Put a marker box
        String anchor = getId() + "log_je_" + i;
        sl.addMarker("log-float-err", "<a name=\"" + anchor + "\">EXCEPTION</a>", "EXCEPTION");

        // Create a bug and store the relevant log lines
        Bug bug = new Bug(Bug.PRIO_JAVA_EXCEPTION_SYSTEM_LOG, sl.ts, sl.msg);
        new Block(bug).add(new Link(sl.getAnchor(), "(link to log)"));
        DocNode log = new Block(bug).addStyle("log");
        log.add(sl.copy());
        int lastLine = firstLine;
        while (true) {
            int next = findNextLine(lastLine, 1);
            if (next < 0) break;
            lastLine = next;
            LogLine sl2 = getParsedLine(lastLine);
            log.add(sl2.copy());
        }
        bug.setAttr("firstLine", firstLine);
        bug.setAttr("lastLine", lastLine);
        bug.setAttr("section", s);
        br.addBug(bug);
    }

    private int findNextLine(int idx, int dir) {
        if (dir == 0) return -1; // Just to be safe, avoid infinite loop
        LogLine pl = getParsedLine(idx);
        while (true) {
            idx += dir;
            if (idx < 0 || idx >= getParsedLineCount()) {
                // Reached the end
                return -1;
            }
            LogLine nl = getParsedLine(idx);
            if (!nl.ok) continue;
            if (Math.abs(nl.ts - pl.ts) > 500) {
                return -1; // The timestamps are too far away
            }
            if (pl.level == nl.level && pl.tag.equals(nl.tag)) {
                return idx; // found a match
            }
        }
    }

    private void analyzeStrictMode(LogLine sl, int i, BugReportModule br, Section s) {
        // Check previous line
        if (i > 0) {
            LogLine sl2 = getParsedLine(i - 1);
            if (sl2.ok && sl2.level == 'E' && sl2.tag.equals("StrictMode")) {
                // Found the middle, ignore it
                return;
            }
        }

        // Put a marker box
        String anchor = getId() + "log_strictmode_" + i;
        sl.addMarker("log-float-err", "<a name=\"" + anchor + "\">StrictMode</a>", "StrictMode");

        // Create a bug and store the relevant log lines
        String title = sl.msg;
        int idx = title.indexOf('.');
        if (idx > 0) {
            title = title.substring(0, idx);
        }
        Bug bug = new Bug(Bug.PRIO_STRICTMODE, sl.ts, "StrictMode: " + title);
        bug.setAttr("firstLine", i);
        new Block(bug).add(new Link(sl.getAnchor(), "(link to log)"));
        DocNode log = new Block(bug).addStyle("log");
        log.add(sl.copy());
        int end = i + 1;
        while (end < s.getLineCount()) {
            LogLine sl2 = getParsedLine(end);
            if (!sl2.ok) break;
            if (!sl2.tag.equals("StrictMode")) break;
            if (sl2.level != 'E') break;
            log.add(sl2.copy());
            end++;
        }
        br.addBug(bug);
    }

    private void analyzeGC(LogLine sl, int i, BugReportModule br, Section s) {
        String line = sl.line;
        int idxFreeAlloc = line.indexOf(" free ");
        int idxExtAlloc = line.indexOf(" external ");
        if (idxFreeAlloc < 0) return;
        idxFreeAlloc += 6;
        int idxFreeAllocEnd = line.indexOf('K', idxFreeAlloc);
        if (idxFreeAllocEnd < 0) return;
        int idxFreeSize = idxFreeAllocEnd + 2;
        int idxFreeSizeEnd = line.indexOf('K', idxFreeSize);
        if (idxFreeSizeEnd < 0) return;
        try {
            int memFreeAlloc = Integer.parseInt(line.substring(idxFreeAlloc, idxFreeAllocEnd));
            int memFreeSize = Integer.parseInt(line.substring(idxFreeSize, idxFreeSizeEnd));
            int memExtAlloc = -1;
            int memExtSize = -1;
            if (idxExtAlloc > 0) {
                idxExtAlloc += 10;
                int idxExtAllocEnd = line.indexOf('K', idxExtAlloc);
                if (idxExtAllocEnd > 0) {
                    int idxExtSize = idxExtAllocEnd + 2;
                    int idxExtSizeEnd = line.indexOf('K', idxExtSize);
                    if (idxExtSizeEnd > 0) {
                        memExtAlloc = Integer.parseInt(line.substring(idxExtAlloc, idxExtAllocEnd));
                        memExtSize = Integer.parseInt(line.substring(idxExtSize, idxExtSizeEnd));
                    }

                }
            }
            GCRecord gc = new GCRecord(sl.ts, sl.pid, memFreeAlloc, memFreeSize, memExtAlloc, memExtSize);
            addGCRecord(sl.pid, gc);
        } catch (NumberFormatException e) {
            // ignore
        }
    }

    private void analyzeRotation(LogLine sl, BugReportModule br, int rot) {
        // Put a marker box
        String icon = "portrait";
        String title = "Phone rotated to portrait mode";
        if (rot == 1) {
            icon = "landscape1";
            title = "Phone rotated to landscape mode";
        } else if (rot == 3) {
            icon = "landscape3";
            title = "Phone rotated to landscape mode";
        }
        icon = "<div class=\"winlist-big-icon winlist-icon-" + icon + "\"> </div>";
        sl.addMarker("log-float-icon", icon, title);
    }

    private void analyzeDisplayed(LogLine sl, BugReportModule br) {
        // Put a marker box
        String name = Util.extract(sl.msg, " ", ":");
        addActivityLaunchMarker(sl, name);
    }

    private void analyzeStartHome(LogLine sl, BugReportModule br) {
        // Put a marker box
        String name = Util.extract(sl.msg, "cmp=", "}");
        addActivityLaunchMarker(sl, name);
    }

}