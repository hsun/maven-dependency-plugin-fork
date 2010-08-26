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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

/**
 * This mojo helps to clean up your dependencyManagement section.
 * 
 * If creates a "clean" version of the pom file with non-used dependencies removed from the
 * dependencyManagement section.
 * 
 * @author <a href="mailto:hsun@shopzilla.com">Hang Sun</a>
 * @version $Id$
 * @goal clean-dep-mgt
 * @requiresDependencyResolution test
 */
public class CleanDepMgt extends AbstractMojo {
    // fields -----------------------------------------------------------------

    /**
     * 
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * Fail the build if a problem is detected.
     * 
     * @parameter expression="${mdep.analyze.failBuild}" default-value="false"
     */
    private boolean failBuild = false;

    /**
     * Default file name used for mojo unless overridden
     * 
     * @parameter expression="${outputDirectory}" default-value="clean.pom.xml"
     * @optional
     * @since 1.0
     */
    private String outputFileName;

    // Mojo methods -----------------------------------------------------------

    /*
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        boolean hasNoError = true;
        if (hasDependencyManagement(project) && hasDependencies(project)) {
            getLog().info("Process project with dependency managed by itself");
            Map dependencyManagementHostProjects = new HashMap();
            dependencyManagementHostProjects.put(project, project);
            hasNoError = cleanDependencyManagements(dependencyManagementHostProjects);
        } else if (hasModules(project) && !hasDependencies(project)) {
            getLog().info("Process project with sub-modules");
            Map dependencyManagementHostProjects = new HashMap();
            findDependencyManagementHosts(project, dependencyManagementHostProjects, false);
            hasNoError = cleanDependencyManagements(dependencyManagementHostProjects);
        } else {
            getLog().info("This project structure is not supported by this mojo");
        }

        if (!hasNoError) {
            if (this.failBuild) {
                throw new MojoExecutionException("Failed to clean up the Dependency Management section.");
            } else {
                getLog().warn("Potential problems found in cleaning up the Dependency Management section.");
            }
        }
    }

    boolean hasModules(MavenProject project) {
        return project.getModules() != null && !project.getModules().isEmpty();
    }

    boolean hasDependencies(MavenProject project) {
        return project.getOriginalModel().getDependencies() != null
                && !project.getOriginalModel().getDependencies().isEmpty();
    }

    boolean hasDependencyManagement(MavenProject project) {
        return project.getOriginalModel().getDependencyManagement() != null
                && project.getOriginalModel().getDependencyManagement().getDependencies() != null
                && !project.getOriginalModel()
                        .getDependencyManagement()
                        .getDependencies()
                        .isEmpty();
    }

    private boolean cleanDependencyManagements(Map dependencyManagementHostProjects) {
        boolean hasNoError = true;
        for (Iterator it = dependencyManagementHostProjects.keySet().iterator(); it.hasNext();) {
            MavenProject hostProject = (MavenProject) it.next();
            List managedProjects = (List) dependencyManagementHostProjects.get(hostProject);
            hasNoError &= cleanDependencyManagement(hostProject, managedProjects);
        }
        return hasNoError;
    }

    private boolean cleanDependencyManagement(MavenProject hostProject, List managedProjects) {
        List allDependencies = new ArrayList();
        for (Iterator it = managedProjects.iterator(); it.hasNext();) {
            MavenProject managedProject = (MavenProject) it.next();
            allDependencies.addAll(managedProject.getOriginalModel().getDependencies());
        }
        removeUnusedDependencies(hostProject, allDependencies);
        return writeCleanDependencyManagement(hostProject);
    }

    void sortDependencies(MavenProject project) {
        Collections.sort(project.getOriginalModel().getDependencyManagement().getDependencies(),
                new Comparator() {

                    String getSignature(Dependency d) {
                        return d.getGroupId() + ":" + d.getArtifactId();
                    }

                    public int compare(Object d1, Object d2) {
                        if (d1 == null) {
                            return -1;
                        } else if (d2 == null) {
                            return 1;
                        } else {
                            return getSignature((Dependency) d1).compareTo(getSignature((Dependency) d2));
                        }
                    }
                });
    }

    private boolean writeCleanDependencyManagement(MavenProject hostProject) {

        String outputDir = hostProject.getBuild().getDirectory();
        File outputDirObj = new File(outputDir);
        if (!outputDirObj.exists()) {
            outputDirObj.mkdirs();
        }

        Writer clean = null;
        try {
            sortDependencies(hostProject);
            clean = new FileWriter(outputDir + File.separator + outputFileName);
            hostProject.writeOriginalModel(clean);
        } catch (IOException e) {
            getLog().error("Failed to write clean pom due to " + e.getMessage());
            return false;
        } finally {
            if (clean != null) {
                try {
                    clean.close();
                } catch (IOException e1) {

                }
            }
        }
        return true;
    }

    private void removeUnusedDependencies(MavenProject hostProject, List allDependencies) {
        List managedDependecies = hostProject.getOriginalModel()
                .getDependencyManagement()
                .getDependencies();
        List unusedDependencies = new ArrayList();
        for (Iterator it = managedDependecies.iterator(); it.hasNext();) {
            Dependency managedDependency = (Dependency) it.next();
            if (isNotUsedDependency(managedDependency, allDependencies)) {
                getLog().info("Removed unused dependency " 
                        + managedDependency.getGroupId() + ":" + managedDependency.getArtifactId());
                unusedDependencies.add(managedDependency);
            }
        }
        int original = managedDependecies.size();
        managedDependecies.removeAll(unusedDependencies);
        int reduced = managedDependecies.size();
        getLog().info("Reduced managed dependencies from " + original + " to " + reduced);
    }

    private boolean isNotUsedDependency(Dependency managedDependency, List allDependencies) {
        for (Iterator it = allDependencies.iterator(); it.hasNext();) {
            Dependency d = (Dependency) it.next();
            if (managedDependency.getGroupId().equals(d.getGroupId())
                    && managedDependency.getArtifactId().equals(d.getArtifactId())) {
                return false;
            }
        }
        return true;
    }

    private void findDependencyManagementHosts(MavenProject project,
            Map dependencyManagementHostProjects, boolean searchUp) {

        if (hasDependencies(project)) {
            // only care if i have dependencies defined
            if (hasDependencyManagement(project)) {
                addDependencyManagementHostProject(dependencyManagementHostProjects,
                        project,
                        project);
            } else if (searchUp) {
                MavenProject hostProject = findDependencyManagementHostProjectInAncestor(project);
                if (hostProject != null) {
                    addDependencyManagementHostProject(dependencyManagementHostProjects,
                            hostProject,
                            project);
                }
            }
        }

        if (project.getCollectedProjects() != null) {
            for (Iterator it = project.getCollectedProjects().iterator(); it.hasNext();) {
                MavenProject childProject = (MavenProject) it.next();
                findDependencyManagementHosts(childProject, dependencyManagementHostProjects, true);
            }
        }
    }

    private MavenProject findDependencyManagementHostProjectInAncestor(MavenProject project) {
        MavenProject hostProject = project;
        while (hostProject != null) {
            hostProject = hostProject.getParent();
            if (hasDependencyManagement(hostProject)) {
                break;
            }
        }
        return hostProject;
    }

    private void addDependencyManagementHostProject(Map dependencyManagementHostProjects,
            MavenProject hostProject, MavenProject managedProject) {
        List managedProjects = (List) dependencyManagementHostProjects.get(hostProject);
        if (managedProjects == null) {
            managedProjects = new ArrayList();
            dependencyManagementHostProjects.put(hostProject, managedProjects);
        }
        managedProjects.add(managedProject);
        getLog().info("Added managed project " + managedProject.getArtifactId() + " to host project " + hostProject.getArtifactId());
    }

    /**
     * @return the failBuild
     */
    public boolean isFailBuild() {
        return this.failBuild;
    }

    /**
     * @param theFailBuild
     *        the failBuild to set
     */
    public void setFailBuild(boolean theFailBuild) {
        this.failBuild = theFailBuild;
    }

    /**
     * @return the project
     */
    public MavenProject getProject() {
        return this.project;
    }

    /**
     * @param theProject
     *        the project to set
     */
    public void setProject(MavenProject theProject) {
        this.project = theProject;
    }

    /**
     * @return the outputFileName
     */
    public String getOutputFileName() {
        return outputFileName;
    }

    /**
     * @param outputFileName
     *        the outputFileName to set
     */
    public void setOutputFileName(String outputFileName) {
        this.outputFileName = outputFileName;
    }

}
