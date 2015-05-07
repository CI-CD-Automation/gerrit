// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.query.change;

import com.google.gerrit.common.TimeUtil;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.TrackingFooters;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.google.gerrit.server.data.QueryStatsAttribute;
import com.google.gerrit.server.events.EventFactory;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.SubmitRuleEvaluator;
import com.google.gerrit.server.query.QueryParseException;
import com.google.gson.Gson;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;

import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Change query implementation that outputs to a stream in the style of an SSH
 * command.
 */
public class OutputStreamQuery {
  private static final Logger log =
      LoggerFactory.getLogger(OutputStreamQuery.class);

  private static final DateTimeFormatter dtf =
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss zzz");

  public static enum OutputFormat {
    TEXT, JSON
  }

  private final ChangeQueryBuilder queryBuilder;
  private final QueryProcessor queryProcessor;
  private final EventFactory eventFactory;
  private final TrackingFooters trackingFooters;
  private final CurrentUser user;

  private OutputFormat outputFormat = OutputFormat.TEXT;
  private boolean includePatchSets;
  private boolean includeCurrentPatchSet;
  private boolean includeApprovals;
  private boolean includeComments;
  private boolean includeFiles;
  private boolean includeCommitMessage;
  private boolean includeDependencies;
  private boolean includeSubmitRecords;
  private boolean includeAllReviewers;

  private OutputStream outputStream = DisabledOutputStream.INSTANCE;
  private PrintWriter out;

  @Inject
  OutputStreamQuery(
      ChangeQueryBuilder queryBuilder,
      QueryProcessor queryProcessor,
      EventFactory eventFactory,
      TrackingFooters trackingFooters,
      CurrentUser user) {
    this.queryBuilder = queryBuilder;
    this.queryProcessor = queryProcessor;
    this.eventFactory = eventFactory;
    this.trackingFooters = trackingFooters;
    this.user = user;
  }

  void setLimit(int n) {
    queryProcessor.setLimit(n);
  }

  public void setStart(int n) {
    queryProcessor.setStart(n);
  }

  public void setIncludePatchSets(boolean on) {
    includePatchSets = on;
  }

  public boolean getIncludePatchSets() {
    return includePatchSets;
  }

  public void setIncludeCurrentPatchSet(boolean on) {
    includeCurrentPatchSet = on;
  }

  public boolean getIncludeCurrentPatchSet() {
    return includeCurrentPatchSet;
  }

  public void setIncludeApprovals(boolean on) {
    includeApprovals = on;
  }

  public void setIncludeComments(boolean on) {
    includeComments = on;
  }

  public void setIncludeFiles(boolean on) {
    includeFiles = on;
  }

  public boolean getIncludeFiles() {
    return includeFiles;
  }

  public void setIncludeDependencies(boolean on) {
    includeDependencies = on;
  }

  public boolean getIncludeDependencies() {
    return includeDependencies;
  }

  public void setIncludeCommitMessage(boolean on) {
    includeCommitMessage = on;
  }

  public void setIncludeSubmitRecords(boolean on) {
    includeSubmitRecords = on;
  }

  public void setIncludeAllReviewers(boolean on) {
    includeAllReviewers = on;
  }

  public void setOutput(OutputStream out, OutputFormat fmt) {
    this.outputStream = out;
    this.outputFormat = fmt;
  }

  public void query(String queryString) throws IOException {
    out = new PrintWriter( //
        new BufferedWriter( //
            new OutputStreamWriter(outputStream, "UTF-8")));
    try {
      if (queryProcessor.isDisabled()) {
        ErrorMessage m = new ErrorMessage();
        m.message = "query disabled";
        show(m);
        return;
      }

      try {
        final QueryStatsAttribute stats = new QueryStatsAttribute();
        stats.runTimeMilliseconds = TimeUtil.nowMs();

        QueryResult results =
            queryProcessor.queryChanges(queryBuilder.parse(queryString));
        ChangeAttribute c = null;
        for (ChangeData d : results.changes()) {
          ChangeControl cc = d.changeControl().forUser(user);

          LabelTypes labelTypes = cc.getLabelTypes();
          c = eventFactory.asChangeAttribute(d.change());
          eventFactory.extend(c, d.change());

          if (!trackingFooters.isEmpty()) {
            eventFactory.addTrackingIds(c,
                trackingFooters.extract(d.commitFooters()));
          }

          if (includeAllReviewers) {
            eventFactory.addAllReviewers(c, d.notes());
          }

          if (includeSubmitRecords) {
            eventFactory.addSubmitRecords(c, new SubmitRuleEvaluator(d)
                .setAllowClosed(true)
                .setAllowDraft(true)
                .evaluate());
          }

          if (includeCommitMessage) {
            eventFactory.addCommitMessage(c, d.commitMessage());
          }

          if (includePatchSets) {
            if (includeFiles) {
              eventFactory.addPatchSets(c, d.patchSets(),
                includeApprovals ? d.approvals().asMap() : null,
                includeFiles, d.change(), labelTypes);
            } else {
              eventFactory.addPatchSets(c, d.patchSets(),
                  includeApprovals ? d.approvals().asMap() : null,
                  labelTypes);
            }
          }

          if (includeCurrentPatchSet) {
            PatchSet current = d.currentPatchSet();
            if (current != null) {
              c.currentPatchSet = eventFactory.asPatchSetAttribute(current);
              eventFactory.addApprovals(c.currentPatchSet,
                  d.currentApprovals(), labelTypes);

              if (includeFiles) {
                eventFactory.addPatchSetFileNames(c.currentPatchSet,
                    d.change(), d.currentPatchSet());
              }
            }
          }

          if (includeComments) {
            eventFactory.addComments(c, d.messages());
            if (includePatchSets) {
              for (PatchSetAttribute attribute : c.patchSets) {
                eventFactory.addPatchSetComments(attribute,  d.publishedComments());
              }
            }
          }

          if (includeDependencies) {
            eventFactory.addDependencies(c, d.change());
          }

          show(c);
        }

        stats.rowCount = results.changes().size();
        stats.moreChanges = results.moreChanges();
        stats.runTimeMilliseconds =
            TimeUtil.nowMs() - stats.runTimeMilliseconds;
        show(stats);
      } catch (OrmException err) {
        log.error("Cannot execute query: " + queryString, err);

        ErrorMessage m = new ErrorMessage();
        m.message = "cannot query database";
        show(m);

      } catch (QueryParseException e) {
        ErrorMessage m = new ErrorMessage();
        m.message = e.getMessage();
        show(m);
      }
    } finally {
      try {
        out.flush();
      } finally {
        out = null;
      }
    }
  }

  private void show(Object data) {
    switch (outputFormat) {
      default:
      case TEXT:
        if (data instanceof ChangeAttribute) {
          out.print("change ");
          out.print(((ChangeAttribute) data).id);
          out.print("\n");
          showText(data, 1);
        } else {
          showText(data, 0);
        }
        out.print('\n');
        break;

      case JSON:
        out.print(new Gson().toJson(data));
        out.print('\n');
        break;
    }
  }

  private void showText(Object data, int depth) {
    for (Field f : fieldsOf(data.getClass())) {
      Object val;
      try {
        val = f.get(data);
      } catch (IllegalArgumentException err) {
        continue;
      } catch (IllegalAccessException err) {
        continue;
      }
      if (val == null) {
        continue;
      }

      showField(f.getName(), val, depth);
    }
  }

  private String indent(int spaces) {
    if (spaces == 0) {
      return "";
    } else {
      return String.format("%" + spaces + "s", " ");
    }
  }

  private void showField(String field, Object value, int depth) {
    final int spacesDepthRatio = 2;
    String indent = indent(depth * spacesDepthRatio);
    out.print(indent);
    out.print(field);
    out.print(':');
    if (value instanceof String && ((String) value).contains("\n")) {
      out.print(' ');
      // Idention for multi-line text is
      // current depth indetion + length of field + length of ": "
      indent = indent(indent.length() + field.length() + spacesDepthRatio);
      out.print(((String) value).replaceAll("\n", "\n" + indent).trim());
      out.print('\n');
    } else if (value instanceof Long && isDateField(field)) {
      out.print(' ');
      out.print(dtf.print(((Long) value) * 1000L));
      out.print('\n');
    } else if (isPrimitive(value)) {
      out.print(' ');
      out.print(value);
      out.print('\n');
    } else if (value instanceof Collection) {
      out.print('\n');
      boolean firstElement = true;
      for (Object thing : ((Collection<?>) value)) {
        // The name of the collection was initially printed at the beginning
        // of this routine.  Beginning at the second sub-element, reprint
        // the collection name so humans can separate individual elements
        // with less strain and error.
        //
        if (firstElement) {
          firstElement = false;
        } else {
          out.print(indent);
          out.print(field);
          out.print(":\n");
        }
        if (isPrimitive(thing)) {
          out.print(' ');
          out.print(value);
          out.print('\n');
        } else {
          showText(thing, depth + 1);
        }
      }
    } else {
      out.print('\n');
      showText(value, depth + 1);
    }
  }

  private static boolean isPrimitive(Object value) {
    return value instanceof String //
        || value instanceof Number //
        || value instanceof Boolean //
        || value instanceof Enum;
  }

  private static boolean isDateField(String name) {
    return "lastUpdated".equals(name) //
        || "grantedOn".equals(name) //
        || "timestamp".equals(name) //
        || "createdOn".equals(name);
  }

  private List<Field> fieldsOf(Class<?> type) {
    List<Field> r = new ArrayList<>();
    if (type.getSuperclass() != null) {
      r.addAll(fieldsOf(type.getSuperclass()));
    }
    r.addAll(Arrays.asList(type.getDeclaredFields()));
    return r;
  }

  static class ErrorMessage {
    public final String type = "error";
    public String message;
  }
}
