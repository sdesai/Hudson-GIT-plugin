package hudson.plugins.git;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.FilePath.FileCallable;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Result;
import hudson.plugins.git.util.BuildChooser;
import hudson.plugins.git.util.BuildData;
import hudson.plugins.git.util.GitUtils;
import hudson.plugins.git.util.IBuildChooser;
import hudson.remoting.VirtualChannel;
import hudson.scm.SCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.util.FormFieldValidator;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.spearce.jgit.lib.ObjectId;
import org.spearce.jgit.transport.RemoteConfig;

public class GitCommitPublisher extends Publisher implements Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    public boolean needsToRunAfterFinalized() {
        return true;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener) throws InterruptedException {

        final SCM scm = build.getProject().getScm();

        if (!(scm instanceof GitSCM)) {
            return false;
        }

        final String projectName = build.getProject().getName();
        final int buildNumber = build.getNumber();
        final Result buildResult = build.getResult();
        final String gitExe = ((GitSCM) scm).getDescriptor().getGitExe();

		final BuildData buildData = ((GitSCM) scm).getBuildData(build, true);

		EnvVars tmp = new EnvVars();
        try {
            tmp = build.getEnvironment(listener);
            // Computer.currentComputer().getEnvironment();
        } catch (InterruptedException e) {
            listener.error("Interrupted exception getting environment .. trying empty environment");
        } catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        final EnvVars environment = tmp;

        BuildData postCommitBuildData = null;

        try {

            postCommitBuildData = build.getWorkspace().act(
                    new FileCallable<BuildData>() {

                        private static final long serialVersionUID = 1L;

                        public BuildData invoke(File workspace, VirtualChannel channel) throws IOException {

                            GitSCM gitSCM = (GitSCM) scm;

                            IGitAPI git = new GitAPI(gitExe, new FilePath(workspace), listener, environment);

							// We delete the old tag generated by the SCM plugin
							String badTag = "hudson-" + projectName  + "-" + buildNumber;
							git.deleteTag(badTag);

							if (buildResult.isBetterOrEqualTo(Result.UNSTABLE)) {

								String tag = projectName + "-" + buildNumber; 
								RemoteConfig remote = gitSCM.getRepositories().get(0);

								String remoteURI = remote.getURIs().get(0).toString();
								String remoteBranch = gitSCM.getBranches().get(0).getName();
								String remoteName = remote.getName(); 

								if ("**".equals(remoteBranch)) {
									// TODO: Really? What does ** really mean
									remoteBranch = "master";
								}

								listener.getLogger().println("Commiting changes, tagging and pushing result of build number " + tag + " to "+ remoteName + ":" + remoteBranch);

								// Add anything new
								git.add(".");

								if (git.hasFilesToCommit()) {
									git.commit("-a", "-m", "Build: " + tag);
								} else {
									listener.getLogger().println("Nothing to commit. No modifications to working tree");
								}

								git.tag(tag, "Build: " + tag);

								List<ObjectId> revs = git.revList("--max-count=1", "HEAD");

								if (!revs.isEmpty() && !revs.get(0).equals(buildData.lastBuild.revision.getSha1())) {
									listener.getLogger().println("Build Revision:" + buildData.lastBuild.revision.getSha1());
									listener.getLogger().println("Post-Commit Revision:" + revs.get(0));
									buildData.lastBuild.revision.setSha1(revs.get(0));
								}

								git.push(remoteURI, "HEAD:" + remoteBranch);
								git.push("--tags", remoteURI);

								return buildData;
							}
							return null;
                        }
                    });

            if (postCommitBuildData != null) {
            	((GitSCM) scm).getBuildData(build, false).lastBuild.revision.setSha1(postCommitBuildData.lastBuild.getSHA1());
            }
            
        } catch (GitException ge) {
            ge.printStackTrace(listener.error("Git Exception: " + ge.getMessage()));
            build.setResult(Result.FAILURE);
            return false;
        } catch (IOException ie) {
            ie.printStackTrace(listener.error("IO Exception: " + ie.getMessage()));
            build.setResult(Result.FAILURE);
            return false;
            
        } catch (Exception e) {
            e.printStackTrace(listener.error("Exception: " + e.getMessage()));
            build.setResult(Result.FAILURE);
            return false;
        }

        return (postCommitBuildData != null) ? true : false;
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            super(GitCommitPublisher.class);
        }

        public String getDisplayName() {
            return "Commit project workspace updates and push code and tags to first remote";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/git/gitCommitPublisher.html";
        }

        /**
         * Performs on-the-fly validation on the file mask wildcard.
         *
         * @param req request
         * @param rsp response
         * @throws IOException
         * @throws ServletException
         */
        public void doCheck(StaplerRequest req, StaplerResponse rsp)
                throws IOException, ServletException {
            new FormFieldValidator.WorkspaceFileMask(req, rsp).process();
        }

        @Override
        public GitCommitPublisher newInstance(StaplerRequest req, JSONObject formData)
                throws FormException {
            return new GitCommitPublisher();
        }

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}
