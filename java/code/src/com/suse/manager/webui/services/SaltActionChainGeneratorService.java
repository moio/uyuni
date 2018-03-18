/**
 * Copyright (c) 2018 SUSE LLC
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.suse.manager.webui.services;

import static com.suse.manager.webui.services.SaltConstants.SUMA_STATE_FILES_ROOT_PATH;
import static com.suse.manager.webui.services.SaltServerActionService.PACKAGES_PKGINSTALL;

import com.redhat.rhn.domain.action.ActionChain;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.MinionServerFactory;

import com.suse.manager.webui.utils.AbstractSaltRequisites;
import com.suse.manager.webui.utils.IdentifiableSaltState;
import com.suse.manager.webui.utils.SaltModuleRun;

import com.suse.manager.webui.utils.SaltState;
import com.suse.manager.webui.utils.SaltSystemReboot;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Service to manage the Salt Action Chains generated by Suse Manager.
 */
public class SaltActionChainGeneratorService {

    /** Logger */
    private static final Logger LOG = Logger.getLogger(SaltActionChainGeneratorService.class);

    // Singleton instance of this class
    public static final SaltActionChainGeneratorService INSTANCE = new SaltActionChainGeneratorService();

    public static final String ACTION_STATE_ID_PREFIX = "mgr_actionchain_";
    public static final String ACTION_STATE_ID_ACTION_PREFIX = "_action_";
    public static final String ACTION_STATE_ID_CHUNK_PREFIX = "_chunk_";
    public static final String ACTIONCHAIN_SLS_FOLDER = "actionchains";

    private static final String ACTIONCHAIN_SLS_FILE_PREFIX = "actionchain_";
    private static final String SCRIPTS_DIR = "scripts";

    public static final Pattern ACTION_STATE_PATTERN =
            Pattern.compile(".*\\|-" + ACTION_STATE_ID_PREFIX + "(\\d+)" +
                    ACTION_STATE_ID_ACTION_PREFIX + "(\\d+)" +
                    ACTION_STATE_ID_CHUNK_PREFIX + "(\\d+).*");

    private Path suseManagerStatesFilesRoot;

    /**
     * Default constructor.
     */
    public SaltActionChainGeneratorService() {
        suseManagerStatesFilesRoot = Paths.get(SUMA_STATE_FILES_ROOT_PATH);
    }

    /**
     * Generates SLS files for an Action Chain.
     * @param actionChain the chain
     * @param minion a minion to execute the chain on
     * @param states a list of states
     */
    public void createActionChainSLSFiles(ActionChain actionChain, MinionServer minion, List<SaltState> states) {
        int chunk = 1;
        List<SaltState> fileStates = new LinkedList<>();
        for (SaltState state: states) {
            if (state instanceof AbstractSaltRequisites) {
                lastRef(fileStates).ifPresent(ref -> {
                    ((AbstractSaltRequisites)state).addRequire(ref.getKey(), ref.getValue());
                });
            }
            if (state instanceof IdentifiableSaltState) {
                IdentifiableSaltState modRun = (IdentifiableSaltState)state;
                modRun.setId(modRun.getId() + ACTION_STATE_ID_CHUNK_PREFIX + chunk);
            }
            fileStates.add(state);

            if (mustSplit(state)) {
                fileStates.add(endChunk(actionChain, chunk, lastRef(fileStates)));

                saveChunkSLS(fileStates, minion, actionChain.getId(), chunk);
                chunk++;
                fileStates.clear();
            }
        }
        saveChunkSLS(fileStates, minion, actionChain.getId(), chunk);
    }

    private SaltState endChunk(ActionChain actionChain, int chunk, Optional<Pair<String, String>> lastRef) {
        Map<String, Object> args = new LinkedHashMap<>(2);
        args.put("actionchain_id", actionChain.getId());
        args.put("chunk", chunk + 1);
        SaltModuleRun modRun = new SaltModuleRun("schedule_next_chunk", "mgractionchains.next", args);
        lastRef.ifPresent(ref -> modRun.addRequire(ref.getKey(), ref.getValue()));
        return modRun;
    }

    private Optional<Pair<String, String>> lastRef(List<SaltState> fileStates) {
        if (fileStates.size() > 0) {
            SaltState lastState = fileStates.get(fileStates.size() - 1);
            return lastState.getData().entrySet().stream().findFirst().map(entry -> {
                String prevMod = ((Map<String, ?>)entry.getValue()).entrySet().stream().findFirst().map(ent -> {
                    String[] stateMod = ent.getKey().split("\\.");
                    if (stateMod.length == 2) {
                        return stateMod[0];
                    }
                    else {
                        // TODO throw err ?
                    }
                    return "";
                }).orElse(""); // TODO orElse throw err ?
                return new ImmutablePair<>(prevMod, entry.getKey());
            });
        }
        return Optional.empty();
    }

    private boolean mustSplit(SaltState state) {
        boolean split = false;
        if (state instanceof SaltModuleRun) {
            SaltModuleRun moduleRun = (SaltModuleRun)state;

            if (moduleRun.getArgs() != null && PACKAGES_PKGINSTALL.equals(moduleRun.getArgs().get("mods"))) {
                // TODO check if salt pkg is updated in order to split
            }
            if ("system.reboot".equalsIgnoreCase(moduleRun.getName())) {
                split = true;
            }

        }
        else if (state instanceof SaltSystemReboot) {
            split = true;
        }
        return split;
    }

    /**
     * Cleans up generated SLS files.
     * @param actionChainId an Action Chain ID
     * @param minionId a minion ID
     * @param chunk the chunk number
     * @param actionChainFailed whether the Action Chain failed or not
     */
    public void removeActionChainSLSFiles(Long actionChainId, String minionId, Integer chunk,
        Boolean actionChainFailed) {
        MinionServerFactory.findByMinionId(minionId).ifPresent(minionServer -> {
            Path targetDir = Paths.get(suseManagerStatesFilesRoot.toString(), ACTIONCHAIN_SLS_FOLDER);
            Path targetFilePath = Paths.get(targetDir.toString(),
                    getActionChainSLSFileName(actionChainId, minionServer, chunk));
            // Add specified SLS chunk file to remove list
            List<Path> filesToDelete = new ArrayList<>();
            filesToDelete.add(targetFilePath);
            // Add possible script files to remove list
            Path scriptsDir = Paths.get(targetDir.toString(), SCRIPTS_DIR);
            String filePattern = ACTIONCHAIN_SLS_FILE_PREFIX + actionChainId +
                    "_" + minionServer.getMachineId() + "_";
            String scriptPattern = "script_suma_actionchain_" + actionChainId +
                    "_chunk_" + chunk;
            try {
                //FIXME: script files are reused by multiple minions
                //for (Path path : Files.list(scriptsDir)
                //        .filter(path -> path.toString().startsWith(
                //                Paths.get(scriptsDir.toString(), scriptPattern).toString()))
                //        .collect(Collectors.toList())) {
                //    filesToDelete.add(path);
                //}
                // Add also next SLS chunks because the Action Chain failed and these
                // files are not longer needed.
                if (actionChainFailed) {
                    //FIXME: script files are reused by multiple minions
                    //filesToDelete.addAll(Files.list(targetDir)
                    //        .filter(path -> path.toString().startsWith(
                    //                Paths.get(targetDir.toString(), filePattern).toString()))
                    //        .collect(Collectors.toList()));
                    filesToDelete.addAll(Files.list(scriptsDir)
                            .filter(path -> path.toString().startsWith(
                                    Paths.get(scriptsDir.toString(), scriptPattern).toString()))
                            .collect(Collectors.toList()));
                }
                // Remove the files
                for (Path path : filesToDelete) {
                    Files.deleteIfExists(path);
                }
            }
            catch (IOException e) {
                LOG.error(e.getMessage(), e);
            }
        });
    }

    /**
     * Generate file name for the action chain chunk file.
     * Public only for unit tests.
     *
     * @param actionChainId an Action Chain ID
     * @param minionServer a minion instance
     * @param chunk a chunk number
     * @return the file name
     */
    public String getActionChainSLSFileName(Long actionChainId, MinionServer minionServer, Integer chunk) {
        return (ACTIONCHAIN_SLS_FILE_PREFIX + Long.toString(actionChainId) +
                "_" + minionServer.getMachineId() + "_" + Integer.toString(chunk) + ".sls");
    }

    private void saveChunkSLS(List<SaltState> states, MinionServer minion, long actionChainId, int chunk) {
        Path targetDir = Paths.get(suseManagerStatesFilesRoot.toString(), ACTIONCHAIN_SLS_FOLDER);
        try {
            Files.createDirectories(targetDir);
        }
        catch (IOException e) {
            LOG.error("Could not create action chain directory " + targetDir, e);
            throw new RuntimeException(e);
        }
        Path targetFilePath = Paths.get(targetDir.toString(),
                getActionChainSLSFileName(actionChainId, minion, chunk));

        try (Writer slsWriter = new FileWriter(targetFilePath.toFile());
             Writer slsBufWriter = new BufferedWriter(slsWriter)) {
            com.suse.manager.webui.utils.SaltStateGenerator saltStateGenerator =
                    new com.suse.manager.webui.utils.SaltStateGenerator(slsBufWriter);
            saltStateGenerator.generate(states.toArray(new SaltState[states.size()]));
        }
        catch (IOException e) {
            LOG.error("Could not write action chain sls " + targetFilePath, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * @param suseManagerStatesFilesRootIn to set
     */
    public void setSuseManagerStatesFilesRoot(Path suseManagerStatesFilesRootIn) {
        this.suseManagerStatesFilesRoot = suseManagerStatesFilesRootIn;
    }

    public static String createStateId(long actionChainId, Long actionId) {
        return ACTION_STATE_ID_PREFIX + actionChainId +
                ACTION_STATE_ID_ACTION_PREFIX + actionId;
    }

    public static final class ActionChainStateId {

        long actionChainId;
        long actionId;
        int chunk;

        public ActionChainStateId(long actionChainId, long actionId, int chunk) {
            this.actionChainId = actionChainId;
            this.actionId = actionId;
            this.chunk = chunk;
        }

        /**
         * @return actionChainId to get
         */
        public long getActionChainId() {
            return actionChainId;
        }

        /**
         * @return actionId to get
         */
        public long getActionId() {
            return actionId;
        }

        /**
         * @return chunk to get
         */
        public int getChunk() {
            return chunk;
        }
    }

    public static Optional<ActionChainStateId> parseActionChainStateId(String stateId) {
        Matcher m = ACTION_STATE_PATTERN.matcher(stateId);
        if (m.find() && m.groupCount() == 3) {
            try {
                return Optional.of(
                        new ActionChainStateId(
                                Long.parseLong(m.group(1)),
                                Long.parseLong(m.group(2)),
                                Integer.parseInt(m.group(3))
                        )
                );
            }
            catch (NumberFormatException e) {
                LOG.error("Error parsing action chain state id: " + stateId, e);
            }
        }
        return Optional.empty();
    }
}
