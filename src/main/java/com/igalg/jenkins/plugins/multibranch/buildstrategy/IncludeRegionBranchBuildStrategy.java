/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 igalg
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.igalg.jenkins.plugins.multibranch.buildstrategy;


import com.cloudbees.jenkins.plugins.bitbucket.PullRequestSCMRevision;
import hudson.Extension;
import hudson.plugins.git.GitChangeSet;
import hudson.scm.SCM;
import jenkins.branch.BranchBuildStrategyDescriptor;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.plugins.git.GitSCMFileSystem;
import jenkins.scm.api.*;
import org.apache.commons.collections4.map.ReferenceMap;
import org.apache.tools.ant.types.selectors.SelectorUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class IncludeRegionBranchBuildStrategy extends BranchBuildStrategyExtension {

    private static final Logger logger = Logger.getLogger(IncludeRegionBranchBuildStrategy.class.getName());

    private static final Map<String, List<String>> changedFilesCache = Collections.synchronizedMap(new ReferenceMap<>());

    private final String includedRegions;
    private final String excludedBranch;

    @DataBoundConstructor
    public IncludeRegionBranchBuildStrategy(String includedRegions, String excludedBranch) {
        this.includedRegions = includedRegions;
        this.excludedBranch = excludedBranch.trim();
    }

    private List<String> getChangedFiles(SCMSource source, SCMHead head, SCMRevision currRevision, SCMRevision prevRevision, String excludedBranch) {
        String key = prevRevision + " -> " + currRevision + " - " + excludedBranch;
        return changedFilesCache.computeIfAbsent(key, k -> {
            try {
                return computeChangedFiles(source, head, currRevision, prevRevision, excludedBranch);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<String> computeChangedFiles(SCMSource source, SCMHead head, SCMRevision currRevision, SCMRevision prevRevision, String excludedBranch) throws Exception {
        // build SCM object
        SCM scm = source.build(head, currRevision);


        // Verify source owner
        SCMSourceOwner owner = source.getOwner();
        if (owner == null) {
            logger.severe("Error verify SCM source owner");
            return null;
        }


        // Build SCM file system
        SCMFileSystem fileSystem = buildSCMFileSystem(source, head, currRevision, scm, owner);
        if (fileSystem == null) {
            logger.severe("Error build SCM file system");
            return null;
        }

        List<GitChangeSet> changeSets = getGitChangeSetListFromPrevious(fileSystem, head, prevRevision);

        if (excludedBranch != null && !excludedBranch.isEmpty() && !excludedBranch.equals(head.getName())) {
            logger.info("Excluding commits in branch [" + excludedBranch + "]");

            GitSCMFileSystem gitFs = (GitSCMFileSystem) fileSystem;
            String remote = gitFs.invoke(repo -> repo.getRemoteNames().stream().findFirst().get());
            String excludedRevisionHash = gitFs.invoke(repo -> repo.findRef(remote + "/" + excludedBranch).getObjectId().name());
            AbstractGitSCMSource.SCMRevisionImpl excludedRevision = new AbstractGitSCMSource.SCMRevisionImpl(new SCMHead(excludedBranch), excludedRevisionHash);
            logger.info("Excluded branch resolved to [" + excludedRevision + "]");

            List<GitChangeSet> changeSetsNotExcluded = getGitChangeSetListFromPrevious(fileSystem, head, excludedRevision);
            Set<String> revisionsNotExcluded = changeSetsNotExcluded.stream().map(GitChangeSet::getRevision).collect(Collectors.toSet());
            List<GitChangeSet> filteredRevisions = changeSets.stream().filter(cs -> revisionsNotExcluded.contains(cs.getRevision())).collect(Collectors.toList());

            logger.info("Number of changesets before exclusion: " + changeSets.size());
            logger.info("Number of changesets not in exclusion: " + changeSetsNotExcluded.size());
            logger.info("Number of changesets in intersection: " + filteredRevisions.size());

            changeSets = filteredRevisions;
        }

        return new ArrayList<String>(collectAllAffectedFiles(changeSets));
    }

    public String getIncludedRegions() {
        return includedRegions;
    }

    public String getExcludedBranch() {
        return excludedBranch;
    }

    /**
     * Determine if build is required by checking if any of the commit affected files is in the include regions.
     *
     * @return true if  there is at least one affected file in the include regions
     */
    @Override
    public boolean isAutomaticBuild(SCMSource source, SCMHead head, SCMRevision currRevision, SCMRevision prevRevision) {
        try {

            // handle 'initial' builds where no prevRevision is known (new branches and PRs)
            if (prevRevision == null) {
                if (currRevision instanceof PullRequestSCMRevision) {
                    logger.info("New pull request detected - setting prevRevision to target");
                    PullRequestSCMRevision<?> prRevision = (PullRequestSCMRevision<?>) currRevision;
                    currRevision = prRevision.getPull();
                    prevRevision = prRevision.getTarget();
                    logger.info("PR: curr: " + currRevision + " prev: " + prevRevision);
                } else {
                    logger.info("New non-PR branch detected - triggering initial index build");
                    return true;
                }
            }

            List<String> includedRegionsList = Arrays.stream(includedRegions.split("\n")).map(e -> e.trim()).collect(Collectors.toList());

            logger.info(String.format("Included regions: %s", includedRegionsList));

            // No regions included cancel the build
            if (includedRegionsList.isEmpty()) return false;

            List<String> changedFiles = getChangedFiles(source, head, currRevision, prevRevision, excludedBranch);
            if (changedFiles == null) return false;

            // If there is match for at least one file run the build
            for (String filePath : changedFiles) {
                for (String includedRegion : includedRegionsList) {
                    if (SelectorUtils.matchPath(includedRegion, filePath)) {
                        logger.info("Matched included region:" + includedRegion + " with file path:" + filePath);
                        return true;
                    } else {
                        logger.fine("Not matched included region:" + includedRegion + " with file path:" + filePath);
                    }
                }
            }


            return false;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unexpected exception", e);
            return false;
        }


    }

    @Extension
    public static class DescriptorImpl extends BranchBuildStrategyDescriptor {
        public String getDisplayName() {
            return "Build included regions strategy";
        }
    }

}
