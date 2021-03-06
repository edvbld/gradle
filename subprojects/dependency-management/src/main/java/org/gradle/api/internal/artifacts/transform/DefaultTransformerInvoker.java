/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.artifacts.transform.TransformationWorkspaceProvider.TransformationWorkspace;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.internal.origin.OriginMetadata;
import org.gradle.internal.Try;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.change.Change;
import org.gradle.internal.change.ChangeVisitor;
import org.gradle.internal.change.SummarizingChangeContainer;
import org.gradle.internal.classloader.ClassLoaderHierarchyHasher;
import org.gradle.internal.execution.CacheHandler;
import org.gradle.internal.execution.ExecutionOutcome;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.WorkExecutor;
import org.gradle.internal.execution.history.AfterPreviousExecutionState;
import org.gradle.internal.execution.history.ExecutionHistoryStore;
import org.gradle.internal.execution.history.changes.AbstractFingerprintChanges;
import org.gradle.internal.execution.history.changes.ExecutionStateChanges;
import org.gradle.internal.execution.history.changes.InputFileChanges;
import org.gradle.internal.execution.impl.steps.UpToDateResult;
import org.gradle.internal.file.TreeType;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileCollectionFingerprinter;
import org.gradle.internal.fingerprint.FileCollectionFingerprinterRegistry;
import org.gradle.internal.fingerprint.OutputNormalizer;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshotter;
import org.gradle.internal.snapshot.ValueSnapshot;
import org.gradle.internal.snapshot.impl.ImplementationSnapshot;
import org.gradle.internal.time.Time;
import org.gradle.internal.time.Timer;
import org.gradle.util.GFileUtils;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class DefaultTransformerInvoker implements TransformerInvoker {

    private final FileSystemSnapshotter fileSystemSnapshotter;
    private final WorkExecutor<UpToDateResult> workExecutor;
    private final ArtifactTransformListener artifactTransformListener;
    private final CachingTransformationWorkspaceProvider immutableTransformationWorkspaceProvider;
    private final FileCollectionFingerprinterRegistry fileCollectionFingerprinterRegistry;
    private final FileCollectionFactory fileCollectionFactory;
    private final FileCollectionFingerprinter outputFingerprinter;
    private final ClassLoaderHierarchyHasher classLoaderHierarchyHasher;
    private final ProjectFinder projectFinder;
    private final boolean useTransformationWorkspaces;

    public DefaultTransformerInvoker(WorkExecutor<UpToDateResult> workExecutor,
                                     FileSystemSnapshotter fileSystemSnapshotter,
                                     ArtifactTransformListener artifactTransformListener,
                                     CachingTransformationWorkspaceProvider immutableTransformationWorkspaceProvider,
                                     FileCollectionFingerprinterRegistry fileCollectionFingerprinterRegistry,
                                     FileCollectionFactory fileCollectionFactory,
                                     ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
                                     ProjectFinder projectFinder,
                                     boolean useTransformationWorkspaces) {
        this.workExecutor = workExecutor;
        this.fileSystemSnapshotter = fileSystemSnapshotter;
        this.artifactTransformListener = artifactTransformListener;
        this.immutableTransformationWorkspaceProvider = immutableTransformationWorkspaceProvider;
        this.fileCollectionFingerprinterRegistry = fileCollectionFingerprinterRegistry;
        this.fileCollectionFactory = fileCollectionFactory;
        this.classLoaderHierarchyHasher = classLoaderHierarchyHasher;
        this.projectFinder = projectFinder;
        this.useTransformationWorkspaces = useTransformationWorkspaces;
        outputFingerprinter = fileCollectionFingerprinterRegistry.getFingerprinter(OutputNormalizer.class);
    }

    @Override
    public Try<ImmutableList<File>> invoke(Transformer transformer, File inputArtifact, ArtifactTransformDependencies dependencies, TransformationSubject subject) {
        FileCollectionFingerprinter dependencyFingerprinter = fileCollectionFingerprinterRegistry.getFingerprinter(transformer.getInputArtifactDependenciesNormalizer());
        CurrentFileCollectionFingerprint dependenciesFingerprint = dependencies.fingerprint(dependencyFingerprinter);
        ProjectInternal producerProject = determineProducerProject(subject);
        CachingTransformationWorkspaceProvider workspaceProvider = determineWorkspaceProvider(producerProject);
        FileSystemLocationSnapshot inputArtifactSnapshot = fileSystemSnapshotter.snapshot(inputArtifact);
        FileCollectionFingerprinter inputArtifactFingerprinter = fileCollectionFingerprinterRegistry.getFingerprinter(transformer.getInputArtifactNormalizer());
        String normalizedInputPath = inputArtifactFingerprinter.normalizePath(inputArtifactSnapshot);
        TransformationWorkspaceIdentity identity = getTransformationIdentity(producerProject, inputArtifactSnapshot, normalizedInputPath, transformer, dependenciesFingerprint);
        return workspaceProvider.withWorkspace(identity, (identityString, workspace) -> {
            return fireTransformListeners(transformer, subject, () -> {
                ImplementationSnapshot implementationSnapshot = ImplementationSnapshot.of(transformer.getImplementationClass(), classLoaderHierarchyHasher);
                CurrentFileCollectionFingerprint inputArtifactFingerprint = inputArtifactFingerprinter.fingerprint(ImmutableList.of(inputArtifactSnapshot));
                TransformerExecution execution = new TransformerExecution(
                    transformer,
                    implementationSnapshot,
                    workspace,
                    identityString,
                    workspaceProvider.getExecutionHistoryStore(),
                    fileCollectionFactory,
                    inputArtifact,
                    inputArtifactFingerprint,
                    dependencies,
                    dependenciesFingerprint,
                    outputFingerprinter
                );
                UpToDateResult outcome = workExecutor.execute(execution);
                return execution.getResult(outcome);
            });
        });
    }

    private TransformationWorkspaceIdentity getTransformationIdentity(@Nullable ProjectInternal project, FileSystemLocationSnapshot inputArtifactSnapshot, String inputArtifactPath, Transformer transformer, CurrentFileCollectionFingerprint dependenciesFingerprint) {
        return project == null
            ? getImmutableTransformationIdentity(inputArtifactPath, inputArtifactSnapshot, transformer, dependenciesFingerprint)
            : getMutableTransformationIdentity(inputArtifactSnapshot, transformer, dependenciesFingerprint);
    }

    private TransformationWorkspaceIdentity getImmutableTransformationIdentity(String inputArtifactPath, FileSystemLocationSnapshot inputArtifactSnapshot, Transformer transformer, CurrentFileCollectionFingerprint dependenciesFingerprint) {
        return new ImmutableTransformationWorkspaceIdentity(
            inputArtifactPath,
            inputArtifactSnapshot.getHash(),
            transformer.getSecondaryInputHash(),
            dependenciesFingerprint.getHash()
        );
    }

    private TransformationWorkspaceIdentity getMutableTransformationIdentity(FileSystemLocationSnapshot inputArtifactSnapshot, Transformer transformer, CurrentFileCollectionFingerprint dependenciesFingerprint) {
        return new MutableTransformationWorkspaceIdentity(
            inputArtifactSnapshot.getAbsolutePath(),
            transformer.getSecondaryInputHash(),
            dependenciesFingerprint.getHash()
        );
    }

    private CachingTransformationWorkspaceProvider determineWorkspaceProvider(@Nullable ProjectInternal producerProject) {
        if (producerProject == null) {
            return immutableTransformationWorkspaceProvider;
        }
        return producerProject.getServices().get(CachingTransformationWorkspaceProvider.class);
    }

    @Nullable
    private ProjectInternal determineProducerProject(TransformationSubject subject) {
        if (!useTransformationWorkspaces || !subject.getProducer().isPresent()) {
            return null;
        }
        ProjectComponentIdentifier projectComponentIdentifier = subject.getProducer().get();
        return projectFinder.findProject(projectComponentIdentifier.getBuild(), projectComponentIdentifier.getProjectPath());
    }

    private Try<ImmutableList<File>> fireTransformListeners(Transformer transformer, TransformationSubject subject, Supplier<Try<ImmutableList<File>>> execution) {
        artifactTransformListener.beforeTransformerInvocation(transformer, subject);
        try {
            return execution.get();
        } finally {
            artifactTransformListener.afterTransformerInvocation(transformer, subject);
        }
    }

    private static class TransformerExecution implements UnitOfWork {
        private static final String INPUT_ARTIFACT_PROPERTY_NAME = "inputArtifact";
        private static final String DEPENDENCIES_PROPERTY_NAME = "inputArtifactDependencies";
        private static final String SECONDARY_INPUTS_HASH_PROPERTY_NAME = "inputPropertiesHash";
        private static final String OUTPUT_DIRECTORY_PROPERTY_NAME = "outputDirectory";
        private static final String RESULTS_FILE_PROPERTY_NAME = "resultsFile";
        private static final String INPUT_FILE_PATH_PREFIX = "i/";
        private static final String OUTPUT_FILE_PATH_PREFIX = "o/";

        private final Transformer transformer;
        private final ImplementationSnapshot implementationSnapshot;
        private final TransformationWorkspace workspace;
        private final File inputArtifact;
        private final String identityString;
        private final ExecutionHistoryStore executionHistoryStore;
        private final FileCollectionFactory fileCollectionFactory;
        private final ArtifactTransformDependencies dependencies;
        private final ImmutableSortedMap<String, ValueSnapshot> inputSnapshots;
        private final ImmutableSortedMap<String, CurrentFileCollectionFingerprint> inputFileFingerprints;
        private final FileCollectionFingerprinter outputFingerprinter;
        private final Timer executionTimer;

        public TransformerExecution(
            Transformer transformer,
            ImplementationSnapshot implementationSnapshot,
            TransformationWorkspace workspace,
            String identityString,
            ExecutionHistoryStore executionHistoryStore,
            FileCollectionFactory fileCollectionFactory,
            File inputArtifact,
            CurrentFileCollectionFingerprint inputArtifactFingerprint,
            ArtifactTransformDependencies dependencies,
            CurrentFileCollectionFingerprint dependenciesFingerprint,
            FileCollectionFingerprinter outputFingerprinter
        ) {
            this.implementationSnapshot = implementationSnapshot;
            this.fileCollectionFactory = fileCollectionFactory;
            this.inputArtifact = inputArtifact;
            this.transformer = transformer;
            this.workspace = workspace;
            this.identityString = "transform/" + identityString;
            this.executionHistoryStore = executionHistoryStore;
            this.dependencies = dependencies;
            this.inputSnapshots = ImmutableSortedMap.of(
                // Emulate secondary inputs as a single property for now
                SECONDARY_INPUTS_HASH_PROPERTY_NAME, ImplementationSnapshot.of("secondary inputs", transformer.getSecondaryInputHash())
            );
            this.inputFileFingerprints = createInputFileFingerprints(inputArtifactFingerprint, dependenciesFingerprint);
            this.outputFingerprinter = outputFingerprinter;
            this.executionTimer = Time.startTimer();
        }

        private static ImmutableSortedMap<String, CurrentFileCollectionFingerprint> createInputFileFingerprints(
            CurrentFileCollectionFingerprint inputArtifactFingerprint,
            CurrentFileCollectionFingerprint dependenciesFingerprint
        ) {
            ImmutableSortedMap.Builder<String, CurrentFileCollectionFingerprint> builder = ImmutableSortedMap.naturalOrder();
            builder.put(INPUT_ARTIFACT_PROPERTY_NAME, inputArtifactFingerprint);
            builder.put(DEPENDENCIES_PROPERTY_NAME, dependenciesFingerprint);
            return builder.build();
        }

        @Override
        public ExecutionOutcome execute() {
            File outputDir = workspace.getOutputDirectory();
            File resultsFile = workspace.getResultsFile();
            GFileUtils.cleanDirectory(outputDir);
            GFileUtils.deleteFileQuietly(resultsFile);
            ImmutableList<File> result = transformer.transform(inputArtifact, outputDir, dependencies);
            writeResultsFile(outputDir, resultsFile, result);
            return ExecutionOutcome.EXECUTED;
        }

        private void writeResultsFile(File outputDir, File resultsFile, ImmutableList<File> result) {
            String outputDirPrefix = outputDir.getPath() + File.separator;
            String inputFilePrefix = inputArtifact.getPath() + File.separator;
            Stream<String> relativePaths = result.stream().map(file -> {
                if (file.equals(outputDir)) {
                    return OUTPUT_FILE_PATH_PREFIX;
                }
                if (file.equals(inputArtifact)) {
                    return INPUT_FILE_PATH_PREFIX;
                }
                String absolutePath = file.getAbsolutePath();
                if (absolutePath.startsWith(outputDirPrefix)) {
                    return OUTPUT_FILE_PATH_PREFIX + RelativePath.parse(true, absolutePath.substring(outputDirPrefix.length())).getPathString();
                }
                if (absolutePath.startsWith(inputFilePrefix)) {
                    return INPUT_FILE_PATH_PREFIX + RelativePath.parse(true, absolutePath.substring(inputFilePrefix.length())).getPathString();
                }
                throw new IllegalStateException("Invalid result path: " + absolutePath);
            });
            UncheckedException.callUnchecked(() -> Files.write(resultsFile.toPath(), (Iterable<String>) relativePaths::iterator));
        }

        private Try<ImmutableList<File>> getResult(UpToDateResult result) {
            return result.getOutcome()
                .map(outcome -> loadResultsFile());
        }

        private ImmutableList<File> loadResultsFile() {
            Path transformerResultsPath = workspace.getResultsFile().toPath();
            try {
                ImmutableList.Builder<File> builder = ImmutableList.builder();
                List<String> paths = Files.readAllLines(transformerResultsPath, StandardCharsets.UTF_8);
                for (String path : paths) {
                    if (path.startsWith(OUTPUT_FILE_PATH_PREFIX)) {
                        builder.add(new File(workspace.getOutputDirectory(), path.substring(2)));
                    } else if (path.startsWith(INPUT_FILE_PATH_PREFIX)) {
                        builder.add(new File(inputArtifact, path.substring(2)));
                    } else {
                        throw new IllegalStateException("Cannot parse result path string: " + path);
                    }
                }
                return builder.build();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Optional<Duration> getTimeout() {
            return Optional.empty();
        }

        @Override
        public void visitOutputProperties(OutputPropertyVisitor visitor) {
            visitor.visitOutputProperty(OUTPUT_DIRECTORY_PROPERTY_NAME, TreeType.DIRECTORY, fileCollectionFactory.fixed(workspace.getOutputDirectory()));
            visitor.visitOutputProperty(RESULTS_FILE_PROPERTY_NAME, TreeType.FILE, fileCollectionFactory.fixed(workspace.getResultsFile()));
        }

        @Override
        public long markExecutionTime() {
            return executionTimer.getElapsedMillis();
        }

        @Override
        public void visitLocalState(LocalStateVisitor visitor) {
        }

        @Override
        public void outputsRemovedAfterFailureToLoadFromCache() {
        }

        @Override
        public CacheHandler createCacheHandler() {
            Hasher hasher = Hashing.newHasher();
            for (Map.Entry<String, ValueSnapshot> entry : inputSnapshots.entrySet()) {
                hasher.putString(entry.getKey());
                entry.getValue().appendToHasher(hasher);
            }
            for (Map.Entry<String, CurrentFileCollectionFingerprint> entry : inputFileFingerprints.entrySet()) {
                hasher.putString(entry.getKey());
                hasher.putHash(entry.getValue().getHash());
            }
            TransformerExecutionBuildCacheKey cacheKey = new TransformerExecutionBuildCacheKey(hasher.hash());
            return new CacheHandler() {
                @Override
                public <T> Optional<T> load(Function<BuildCacheKey, T> loader) {
                    if (!transformer.isCacheable()) {
                        return Optional.empty();
                    }
                    return Optional.ofNullable(loader.apply(cacheKey));
                }

                @Override
                public void store(Consumer<BuildCacheKey> storer) {
                    if (transformer.isCacheable()) {
                        storer.accept(cacheKey);
                    }
                }
            };
        }

        @Override
        public void persistResult(ImmutableSortedMap<String, CurrentFileCollectionFingerprint> finalOutputs, boolean successful, OriginMetadata originMetadata) {
            if (successful) {
                executionHistoryStore.store(
                    identityString,
                    originMetadata,
                    implementationSnapshot,
                    ImmutableList.of(),
                    inputSnapshots,
                    inputFileFingerprints,
                    finalOutputs,
                    successful
                );
            }
        }

        @Override
        public Optional<ExecutionStateChanges> getChangesSincePreviousExecution() {
            return executionHistoryStore.load(identityString).map(previous -> {
                ImmutableSortedMap<String, CurrentFileCollectionFingerprint> outputsBeforeExecution = snapshotOutputs();
                InputFileChanges inputFileChanges = new InputFileChanges(previous.getInputFileProperties(), inputFileFingerprints);
                AllOutputFileChanges outputFileChanges = new AllOutputFileChanges(previous.getOutputFileProperties(), outputsBeforeExecution);
                return new TransformerExecutionStateChanges(inputFileChanges, outputFileChanges, previous);
            });
        }

        @Override
        public Optional<? extends Iterable<String>> getChangingOutputs() {
            return Optional.of(ImmutableList.of(workspace.getOutputDirectory().getAbsolutePath(), workspace.getResultsFile().getAbsolutePath()));
        }


        @Override
        public ImmutableSortedMap<String, CurrentFileCollectionFingerprint> snapshotAfterOutputsGenerated() {
            return snapshotOutputs();
        }

        private ImmutableSortedMap<String, CurrentFileCollectionFingerprint> snapshotOutputs() {
            CurrentFileCollectionFingerprint outputFingerprint = outputFingerprinter.fingerprint(fileCollectionFactory.fixed(workspace.getOutputDirectory()));
            CurrentFileCollectionFingerprint resultsFileFingerprint = outputFingerprinter.fingerprint(fileCollectionFactory.fixed(workspace.getResultsFile()));
            return ImmutableSortedMap.of(
                OUTPUT_DIRECTORY_PROPERTY_NAME, outputFingerprint,
                RESULTS_FILE_PROPERTY_NAME, resultsFileFingerprint);
        }

        @Override
        public String getIdentity() {
            return identityString;
        }

        @Override
        public void visitOutputTrees(CacheableTreeVisitor visitor) {
            visitor.visitOutputTree(OUTPUT_DIRECTORY_PROPERTY_NAME, TreeType.DIRECTORY, workspace.getOutputDirectory());
            visitor.visitOutputTree(RESULTS_FILE_PROPERTY_NAME, TreeType.FILE, workspace.getResultsFile());
        }

        @Override
        public String getDisplayName() {
            return transformer.getDisplayName() + ": " + inputArtifact;
        }

        private class TransformerExecutionStateChanges implements ExecutionStateChanges {
            private final InputFileChanges inputFileChanges;
            private final AllOutputFileChanges outputFileChanges;
            private final AfterPreviousExecutionState afterPreviousExecutionState;

            public TransformerExecutionStateChanges(InputFileChanges inputFileChanges, AllOutputFileChanges outputFileChanges, AfterPreviousExecutionState afterPreviousExecutionState) {
                this.inputFileChanges = inputFileChanges;
                this.outputFileChanges = outputFileChanges;
                this.afterPreviousExecutionState = afterPreviousExecutionState;
            }

            @Override
            public Iterable<Change> getInputFilesChanges() {
                return ImmutableList.of();
            }

            @Override
            public void visitAllChanges(ChangeVisitor visitor) {
                new SummarizingChangeContainer(inputFileChanges, outputFileChanges).accept(visitor);
            }

            @Override
            public boolean isRebuildRequired() {
                return true;
            }

            @Override
            public AfterPreviousExecutionState getPreviousExecution() {
                return afterPreviousExecutionState;
            }
        }

        private class TransformerExecutionBuildCacheKey implements BuildCacheKey {
            private final HashCode hashCode;

            public TransformerExecutionBuildCacheKey(HashCode hashCode) {
                this.hashCode = hashCode;
            }

            @Override
            public String getHashCode() {
                return hashCode.toString();
            }

            @Override
            public String getDisplayName() {
                return getHashCode() + " for transformer " + TransformerExecution.this.getDisplayName();
            }
        }
    }

    private static class AllOutputFileChanges extends AbstractFingerprintChanges {

        public AllOutputFileChanges(ImmutableSortedMap<String, FileCollectionFingerprint> previous, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> current) {
            super(previous, current, "Output");
        }

        @Override
        public boolean accept(ChangeVisitor visitor) {
            return accept(visitor, true);
        }
    }

    private static class ImmutableTransformationWorkspaceIdentity implements TransformationWorkspaceIdentity {
        private final String inputArtifactPath;
        private final HashCode inputArtifactHash;
        private final HashCode secondaryInputHash;
        private final HashCode dependenciesHash;

        public ImmutableTransformationWorkspaceIdentity(String inputArtifactPath, HashCode inputArtifactHash, HashCode secondaryInputHash, HashCode dependenciesHash) {
            this.inputArtifactPath = inputArtifactPath;
            this.inputArtifactHash = inputArtifactHash;
            this.secondaryInputHash = secondaryInputHash;
            this.dependenciesHash = dependenciesHash;
        }

        @Override
        public String getIdentity() {
            Hasher hasher = Hashing.newHasher();
            hasher.putString(inputArtifactPath);
            hasher.putHash(inputArtifactHash);
            hasher.putHash(secondaryInputHash);
            hasher.putHash(dependenciesHash);
            return hasher.hash().toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ImmutableTransformationWorkspaceIdentity that = (ImmutableTransformationWorkspaceIdentity) o;

            if (!inputArtifactHash.equals(that.inputArtifactHash)) {
                return false;
            }
            if (!inputArtifactPath.equals(that.inputArtifactPath)) {
                return false;
            }
            if (!secondaryInputHash.equals(that.secondaryInputHash)) {
                return false;
            }
            return dependenciesHash.equals(that.dependenciesHash);
        }

        @Override
        public int hashCode() {
            int result = inputArtifactHash.hashCode();
            result = 31 * result + secondaryInputHash.hashCode();
            result = 31 * result + dependenciesHash.hashCode();
            return result;
        }
    }

    public static class MutableTransformationWorkspaceIdentity implements TransformationWorkspaceIdentity {
        private final String inputArtifactAbsolutePath;
        private final HashCode secondaryInputsHash;
        private final HashCode dependenciesHash;

        public MutableTransformationWorkspaceIdentity(String inputArtifactAbsolutePath, HashCode secondaryInputsHash, HashCode dependenciesHash) {
            this.inputArtifactAbsolutePath = inputArtifactAbsolutePath;
            this.secondaryInputsHash = secondaryInputsHash;
            this.dependenciesHash = dependenciesHash;
        }

        @Override
        public String getIdentity() {
            Hasher hasher = Hashing.newHasher();
            hasher.putString(inputArtifactAbsolutePath);
            hasher.putHash(secondaryInputsHash);
            hasher.putHash(dependenciesHash);
            return hasher.hash().toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            MutableTransformationWorkspaceIdentity that = (MutableTransformationWorkspaceIdentity) o;

            if (!secondaryInputsHash.equals(that.secondaryInputsHash)) {
                return false;
            }
            if (!dependenciesHash.equals(that.dependenciesHash)) {
                return false;
            }
            return inputArtifactAbsolutePath.equals(that.inputArtifactAbsolutePath);
        }

        @Override
        public int hashCode() {
            int result = inputArtifactAbsolutePath.hashCode();
            result = 31 * result + secondaryInputsHash.hashCode();
            result = 31 * result + dependenciesHash.hashCode();
            return result;
        }
    }
}
