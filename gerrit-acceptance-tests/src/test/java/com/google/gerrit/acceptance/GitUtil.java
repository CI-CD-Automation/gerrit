// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.acceptance;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.gerrit.common.FooterConstants;
import com.google.gerrit.reviewdb.client.Project;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig.Host;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.util.FS;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

public class GitUtil {

  public static void initSsh(final TestAccount a) {
    final Properties config = new Properties();
    config.put("StrictHostKeyChecking", "no");
    JSch.setConfig(config);

    // register a JschConfigSessionFactory that adds the private key as identity
    // to the JSch instance of JGit so that SSH communication via JGit can
    // succeed
    SshSessionFactory.setInstance(new JschConfigSessionFactory() {
      @Override
      protected void configure(Host hc, Session session) {
        try {
          final JSch jsch = getJSch(hc, FS.DETECTED);
          jsch.addIdentity("KeyPair", a.privateKey(),
              a.sshKey.getPublicKeyBlob(), null);
        } catch (JSchException e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  public static TestRepository<InMemoryRepository> cloneProject(
      Project.NameKey project, String uri) throws Exception {
    InMemoryRepository dest = new InMemoryRepository.Builder()
        .setRepositoryDescription(new DfsRepositoryDescription(project.get()))
        // SshTransport depends on a real FS to read ~/.ssh/config, but
        // InMemoryRepository by default uses a null FS.
        // TODO(dborowitz): Remove when we no longer depend on SSH.
        .setFS(FS.detect())
        .build();
    Config cfg = dest.getConfig();
    cfg.setString("remote", "origin", "url", uri);
    cfg.setString("remote", "origin", "fetch",
        "+refs/heads/*:refs/remotes/origin/*");
    TestRepository<InMemoryRepository> testRepo = new TestRepository<>(dest);
    FetchResult result = testRepo.git().fetch().setRemote("origin").call();
    String originMaster = "refs/remotes/origin/master";
    if (result.getTrackingRefUpdate(originMaster) != null) {
      testRepo.reset(originMaster);
    }
    return new TestRepository<>(dest);
  }

  public static TestRepository<InMemoryRepository> cloneProject(
      Project.NameKey project, SshSession sshSession) throws Exception {
    return cloneProject(project, sshSession.getUrl() + "/" + project.get());
  }

  public static void fetch(Git git, String spec) throws GitAPIException {
    FetchCommand fetch = git.fetch();
    fetch.setRefSpecs(new RefSpec(spec));
    fetch.call();
  }

  public static PushResult pushHead(Git git, String ref, boolean pushTags)
      throws GitAPIException {
    return pushHead(git, ref, pushTags, false);
  }

  public static PushResult pushHead(Git git, String ref, boolean pushTags,
      boolean force) throws GitAPIException {
    PushCommand pushCmd = git.push();
    pushCmd.setForce(force);
    pushCmd.setRefSpecs(new RefSpec("HEAD:" + ref));
    if (pushTags) {
      pushCmd.setPushTags();
    }
    Iterable<PushResult> r = pushCmd.call();
    return Iterables.getOnlyElement(r);
  }

  public static class Commit {
    private final RevCommit commit;
    private final String changeId;

    Commit(RevCommit commit, String changeId) {
      this.commit = commit;
      this.changeId = changeId;
    }

    public RevCommit getCommit() {
      return commit;
    }

    public String getChangeId() {
      return changeId;
    }
  }

  public static Optional<String> getChangeId(TestRepository<?> tr, ObjectId id)
      throws IOException {
    RevCommit c = tr.getRevWalk().parseCommit(id);
    tr.getRevWalk().parseBody(c);
    List<String> ids = c.getFooterLines(FooterConstants.CHANGE_ID);
    if (ids.isEmpty()) {
      return Optional.absent();
    }
    return Optional.of(ids.get(ids.size() - 1));
  }
}
