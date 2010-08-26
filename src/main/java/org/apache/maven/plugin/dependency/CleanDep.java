package org.apache.maven.plugin.dependency;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalyzer;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalyzerException;

/**
 * This mojo helps to clean up your Dependency section. 
 * 
 * If creates a "clean" version of the pom file with non-used dependencies removed 
 * from the dependency section. 
 *
 * @author <a href="mailto:hsun@shopzilla.com">Hang Sun</a>
 * @version $Id$
 * @goal clean-dep
 * @requiresDependencyResolution test
 */
public class CleanDep
    extends AbstractMojo
{
    // fields -----------------------------------------------------------------

    /**
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * Default location used for mojo unless overridden
     *
     * @parameter expression="${outputDirectory}"
     *            default-value="${project.build.directory}"
     * @optional
     * @since 1.0
     */
    protected File outputDirectory;

    /**
     * Default file name used for mojo unless overridden
     *
     * @parameter expression="${outputDirectory}"
     *            default-value="clean.pom.xml"
     * @optional
     * @since 1.0
     */
    protected String outputFileName;

    /**
     * The Maven project dependency analyzer to use.
     * 
     * @component
     * @required
     * @readonly
     */
    private ProjectDependencyAnalyzer analyzer;

    /**
     * Whether to fail the build if a dependency warning is found.
     * 
     * @parameter expression="${failOnWarning}" default-value="true"
     */
    private boolean failOnWarning;

    /**
     * Output used dependencies
     * 
     * @parameter expression="${verbose}" default-value="false"
     */
    private boolean verbose;

    /**
     * Ignore Runtime,Provide,Test,System scopes for unused dependency analysis
     * 
     * @parameter expression="${ignoreNonCompile}" default-value="false"
     */
    private boolean ignoreNonCompile;

    // Mojo methods -----------------------------------------------------------

    /*
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        if ("pom".equals(project.getPackaging())) {
            getLog().info("Skipping pom project");
            return;
        }

        if (outputDirectory == null || !outputDirectory.exists()) {
            getLog().info("Skipping project with no build directory");
            return;
        }

        boolean warning = cleanupDependencies();

        if (warning && failOnWarning) {
            throw new MojoExecutionException("Dependency problems found");
        }
    }

    private boolean cleanupDependencies() throws MojoExecutionException {
        
        ProjectDependencyAnalysis analysis;
        try {
            analysis = analyzer.analyze(project);
        } catch (ProjectDependencyAnalyzerException exception) {
            throw new MojoExecutionException("Cannot analyze dependencies", exception);
        }

        Set usedUndeclared = analysis.getUsedUndeclaredArtifacts();
        Set unusedDeclared = analysis.getUnusedDeclaredArtifacts();
        if (ignoreNonCompile) {
            getLog().info("ignoreNonCompile is turned on");
            Set filteredUnusedDeclared = new HashSet(unusedDeclared);
            Iterator iter = filteredUnusedDeclared.iterator();
            while (iter.hasNext()) {
                Artifact artifact = (Artifact) iter.next();
                if (!artifact.getScope().equals(Artifact.SCOPE_COMPILE)) {
                    getLog().info("Ignore unused artifact " 
                            + artifact.getGroupId() + ":" + artifact.getGroupId());
                    iter.remove();
                }
            }
            unusedDeclared = filteredUnusedDeclared;
        }

        int originalDependencies = project.getOriginalModel().getDependencies().size();
        int unusedDependencies = removeUnusedDeclared(unusedDeclared);
        int usedUndeclaredDependencies = addUsedUndeclared(usedUndeclared);
        int cleanDependencies = project.getOriginalModel().getDependencies().size();
        getLog().info("Reduced dependencies from " + originalDependencies 
                + " to " + cleanDependencies + "(added: " + usedUndeclaredDependencies + ", removed: " + unusedDependencies);
        sortDependencies();
        return writeCleanPom();
    }

    int removeUnusedDeclared(Set unusedDeclared) {
        if (!unusedDeclared.isEmpty()) {
            Collection toBeRemoved = new ArrayList();
            for (Iterator it = unusedDeclared.iterator(); it.hasNext();) {
                Artifact artifact = (Artifact)it.next();
                for(Iterator it2 = project.getOriginalModel().getDependencies().iterator(); it2.hasNext();) {
                    Dependency dep = (Dependency)it2.next();
                    if (dep.getGroupId().equals(artifact.getGroupId())
                            && dep.getArtifactId().equals(artifact.getArtifactId())) {
                        toBeRemoved.add(dep);
                        break;
                    }
                }
            }
            project.getOriginalModel().getDependencies().removeAll(toBeRemoved);
        }
        return unusedDeclared.size();
    }
    
    int addUsedUndeclared(Set usedUndeclared) {
        if (!usedUndeclared.isEmpty()) {
            Collection toBeAdded = new ArrayList();
            for (Iterator it = usedUndeclared.iterator(); it.hasNext();) {
                Artifact artifact = (Artifact)it.next();
                Dependency dep = new Dependency();
                dep.setArtifactId(artifact.getArtifactId());
                dep.setGroupId(artifact.getGroupId());
                dep.setVersion(artifact.getVersion());
                if (!"compile".equals(artifact.getScope())) {
                    dep.setScope(artifact.getScope());
                }
                toBeAdded.add(dep);
            }
            project.getOriginalModel().getDependencies().addAll(toBeAdded);
        }
        return usedUndeclared.size();
    }

    void sortDependencies() {
        Collections.sort(project.getOriginalModel().getDependencies(), new Comparator() {

            String getSignature(Dependency d) {
                return d.getGroupId() + ":" + d.getArtifactId();
            }

            public int compare(Object d1, Object d2) {
                if (d1 == null) {
                    return -1;
                } else if (d2 == null) {
                    return 1;
                } else {
                    return getSignature((Dependency)d1).compareTo(getSignature((Dependency)d2));
                }
            }
        });
    }
    
    private boolean writeCleanPom() {
        Writer w = null;
        try {
            String outputFile = outputDirectory + File.separator + outputFileName;
            getLog().info("About to create clean pom in: " + outputFile);
            w = new FileWriter(outputFile);
            project.writeOriginalModel(w);
            return false;
        } catch (IOException e) {
            getLog().error("Unable to create clean pom: " + e);
            return true;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (IOException e) {
                }
            }
        }
    }

    /**
     * @return the project
     */
    public MavenProject getProject()
    {
        return this.project;
    }

    /**
     * @param theProject
     *            the project to set
     */
    public void setProject( MavenProject theProject )
    {
        this.project = theProject;
    }

    /**
     * @return the outputDirectory
     */
    public File getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * @param outputDirectory the outputDirectory to set
     */
    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    /**
     * @return the outputFileName
     */
    public String getOutputFileName() {
        return outputFileName;
    }

    /**
     * @param outputFileName the outputFileName to set
     */
    public void setOutputFileName(String outputFileName) {
        this.outputFileName = outputFileName;
    }

    /**
     * @return the analyzer
     */
    public ProjectDependencyAnalyzer getAnalyzer() {
        return analyzer;
    }

    /**
     * @param analyzer the analyzer to set
     */
    public void setAnalyzer(ProjectDependencyAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    /**
     * @return the failOnWarning
     */
    public boolean isFailOnWarning() {
        return failOnWarning;
    }

    /**
     * @param failOnWarning the failOnWarning to set
     */
    public void setFailOnWarning(boolean failOnWarning) {
        this.failOnWarning = failOnWarning;
    }

    /**
     * @return the verbose
     */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * @param verbose the verbose to set
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * @return the ignoreNonCompile
     */
    public boolean isIgnoreNonCompile() {
        return ignoreNonCompile;
    }

    /**
     * @param ignoreNonCompile the ignoreNonCompile to set
     */
    public void setIgnoreNonCompile(boolean ignoreNonCompile) {
        this.ignoreNonCompile = ignoreNonCompile;
    }

}
