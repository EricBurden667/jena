/**
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  See the NOTICE file distributed with this work for additional
 *  information regarding copyright ownership.
 */

package org.seaborne.dboe.trans.bplustree;

import org.seaborne.dboe.DBOpEnvException ;
import org.seaborne.dboe.base.block.BlockMgr ;
import org.seaborne.dboe.base.block.BlockMgrFactory ;
import org.seaborne.dboe.base.block.BlockMgrLogger ;
import org.seaborne.dboe.base.file.BufferChannel ;
import org.seaborne.dboe.base.file.BufferChannelMem ;
import org.seaborne.dboe.base.file.FileFactory ;
import org.seaborne.dboe.base.file.FileSet ;
import org.seaborne.dboe.base.record.RecordFactory ;
import org.seaborne.dboe.base.recordbuffer.RecordBufferPage ;
import org.seaborne.dboe.base.recordbuffer.RecordBufferPageMgr ;
import org.seaborne.dboe.index.RangeIndex ;
import org.seaborne.dboe.sys.Names ;
import org.seaborne.dboe.sys.SystemIndex ;
import org.seaborne.dboe.transaction.txn.ComponentId ;

/** Make BPlusTrees - this code works in close association with the BPlusTree constructor */
public class BPlusTreeFactory {
    private BPlusTreeFactory() {}
    
    /** Create the java structures to correspond to
     * the supplied block managers for the persistent storage.
     * Initialize the persistent storage to the empty B+Tree if it does not exist.
     * This is primitive operation that underpins creation of a sB+Tree.
     */
    public static BPlusTree create(ComponentId id, BPlusTreeParams params, BufferChannel chan, BlockMgr blkMgrNodes, BlockMgr blkMgrLeaves) {
        if ( id == null )
            id = ComponentId.allocLocal() ;
        BPlusTree bpt = attach(id, params, false, chan, blkMgrNodes, blkMgrLeaves) ;
        return bpt ;
    }

    /** Create the java structures to correspond to
     * the supplied block managers for the persistent storage.
     * Initialize the persistent storage to the empty B+Tree if it does not exist.
     */
    public static BPlusTree createNonTxn(BPlusTreeParams params, BufferChannel chan, BlockMgr blkMgrNodes, BlockMgr blkMgrLeaves) {
        // Allocate a random ComponentId
        BPlusTree bpt = create(null, params, chan, blkMgrNodes, blkMgrLeaves) ;
        bpt.nonTransactional() ;
        return bpt ;
    }

    /** Reset an existign B+Tree with difrerent storage units.
     * For each, null means "use same as original" 
     */
    public static BPlusTree rebuild(BPlusTree bpt, BufferChannel chan, BlockMgr blkMgrNodes, BlockMgr blkMgrLeaves) {
        if ( chan == null )
            chan = bpt.getStateManager().getBufferChannel() ;
        if ( blkMgrNodes == null )
            blkMgrNodes = bpt.getNodeManager().getBlockMgr() ;
        if ( blkMgrLeaves == null )
            blkMgrLeaves = bpt.getNodeManager().getBlockMgr() ;
        BPlusTree bpt2 = attach(bpt.getComponentId(), bpt.getParams(), true, chan, blkMgrNodes, blkMgrLeaves) ;
        return bpt2 ;
    }

    public static RangeIndex makeBPlusTree(ComponentId cid, FileSet fs, int blkSize, 
                                           int readCacheSize, int writeCacheSize,
                                           int dftKeyLength, int dftValueLength) {
        RecordFactory recordFactory = makeRecordFactory(dftKeyLength, dftValueLength) ;
        int order = BPlusTreeParams.calcOrder(blkSize, recordFactory.recordLength()) ;
        RangeIndex rIndex = createBPTree(cid, fs, order, blkSize, readCacheSize, writeCacheSize, recordFactory) ;
        return rIndex ;
    }

    public static RecordFactory makeRecordFactory(int keyLen, int valueLen) {
        return new RecordFactory(keyLen, valueLen) ;
    }

    /** Create a B+Tree using defaults */
    public static BPlusTree createBPTree(ComponentId cid, FileSet fileset, RecordFactory factory)
    {
        int readCacheSize = SystemIndex.BlockReadCacheSize ;
        int writeCacheSize = SystemIndex.BlockWriteCacheSize ;
        int blockSize = SystemIndex.BlockSize ;
        if ( fileset.isMem() )
        {
            readCacheSize = 0 ;
            writeCacheSize = 0 ;
            blockSize = SystemIndex.BlockSizeTest ;
        }
        
        return createBPTreeByBlockSize(cid, fileset, blockSize, readCacheSize, writeCacheSize, factory) ; 
    }

    /** Create a B+Tree by BlockSize */
    public static BPlusTree createBPTreeByBlockSize(ComponentId cid, FileSet fileset,
                                                    int blockSize,
                                                    int readCacheSize, int writeCacheSize,
                                                    RecordFactory factory)
    {
        return createBPTree(cid, fileset, -1, blockSize, readCacheSize, writeCacheSize, factory) ; 
    }

    /** Create a B+Tree by Order */
    public static BPlusTree createBPTreeByOrder(ComponentId cid, FileSet fileset,
                                                int order,
                                                int readCacheSize, int writeCacheSize,
                                                RecordFactory factory)
    {
        return createBPTree(cid, fileset, order, -1, readCacheSize, writeCacheSize, factory) ; 
    }

    /** Create a B+Tree by Order */
    public static BPlusTree createBPTreeByOrder(ComponentId cid, FileSet fileset,
                                                int order,
                                                RecordFactory factory)
    {
        return createBPTree(cid, fileset, order, -1, SystemIndex.BlockReadCacheSize, SystemIndex.BlockWriteCacheSize, factory) ; 
    }

    /** Knowing all the parameters, create a B+Tree */
    public static BPlusTree createBPTree(ComponentId cid, FileSet fileset, int order, int blockSize,
                                         int readCacheSize, int writeCacheSize,
                                         RecordFactory factory)
    {
        // ---- Checking
        if (blockSize < 0 && order < 0) throw new IllegalArgumentException("Neither blocksize nor order specified") ;
        if (blockSize >= 0 && order < 0) order = BPlusTreeParams.calcOrder(blockSize, factory.recordLength()) ;
        if (blockSize >= 0 && order >= 0)
        {
            int order2 = BPlusTreeParams.calcOrder(blockSize, factory.recordLength()) ;
            if (order != order2) throw new IllegalArgumentException("Wrong order (" + order + "), calculated = "
                                                                    + order2) ;
        }
    
        // Iffy - does not allow for slop.
        if (blockSize < 0 && order >= 0) {
            // Only in-memory.
            blockSize = BPlusTreeParams.calcBlockSize(order, factory) ;
        }
    
        BPlusTreeParams params = new BPlusTreeParams(order, factory) ;
        BufferChannel bptState = FileFactory.createBufferChannel(fileset, Names.bptExtRoot) ;
        BlockMgr blkMgrNodes = BlockMgrFactory.create(fileset, Names.bptExtTree, blockSize, readCacheSize, writeCacheSize) ;
        BlockMgr blkMgrRecords = BlockMgrFactory.create(fileset, Names.bptExtRecords, blockSize, readCacheSize, writeCacheSize) ;
        return BPlusTreeFactory.create(cid, params, bptState, blkMgrNodes, blkMgrRecords) ;
    }

    /**
     * Create the in-memory structures to correspond to the supplied block
     * managers for the persistent storage. Does not inityalize the B+Tree - it
     * assumes the block managers correspond to an existing B+Tree.
     */
    private static BPlusTree attach(ComponentId id, BPlusTreeParams params,
                                    boolean isReset,
                                    BufferChannel rootData, BlockMgr blkMgrNodes, BlockMgr blkMgrRecords) {
        // Creating and initializing the BPlusTree object is a two stage process.

        // * Create the Java object so it can be in other structures
        //   but it is not fully initialized yet.
        //     Create datastructures being careful not to use the object
        //   Ensure formatted
        // * Initialize.
        BPlusTree bpt = new BPlusTree(id, params) ; 
        
        BPTStateMgr stateMgr = new BPTStateMgr(rootData) ;
        blkMgrNodes.resetAlloc(stateMgr.getNodeBlocksLimit());
        blkMgrRecords.resetAlloc(stateMgr.getRecordsBlocksLimit());
        
        BPTreeNodeMgr nodeManager = new BPTreeNodeMgr(bpt, blkMgrNodes) ;
        
        RecordBufferPageMgr recordPageMgr = new RecordBufferPageMgr(params.getRecordFactory(), blkMgrRecords) ;
        BPTreeRecordsMgr recordsMgr = new BPTreeRecordsMgr(bpt, params.getRecordFactory(), recordPageMgr) ;
        
        createIfAbsent(isReset, stateMgr, nodeManager, recordsMgr) ;
        
        bpt.init(stateMgr, nodeManager, recordsMgr) ;
        if ( BPT.CheckingNode ) {
            nodeManager.startRead();
            BPTreeNode root = nodeManager.getRead(bpt.getRootId(), BPlusTreeParams.RootParent) ;
            root.checkNodeDeep() ;
            nodeManager.release(root) ;
            nodeManager.finishRead();
        }
        return bpt ;
    }

    /** (Testing mainly) Make an in-memory B+Tree, with copy-in, copy-out block managers */
    public static BPlusTree makeMem(int order, int keyLength, int valueLength)
    { return makeMem(null, order, keyLength, valueLength) ; }

    /** (Testing mainly) Make an in-memory B+Tree, with copy-in, copy-out block managers */
    public static BPlusTree makeMem(String name, int order, int keyLength, int valueLength)
    { return makeMem(name, order, -1, keyLength, valueLength) ; }

    /** (Testing mainly) Make an in-memory B+Tree, with copy-in, copy-out block managers */
    public static BPlusTree makeMem(int order, int minDataRecords, int keyLength, int valueLength)
    { return makeMem(null, order, minDataRecords, keyLength, valueLength) ; }

    /** (Testing mainly) Make an in-memory B+Tree, with copy-in, copy-out block managers */
    public static BPlusTree makeMem(String name, int order, int minDataRecords, int keyLength, int valueLength) {
        if ( name == null )
            name = "Mem" ;
        BPlusTreeParams params = new BPlusTreeParams(order, keyLength, valueLength) ;
    
        int blkSize ;
        if ( minDataRecords > 0 ) {
            int maxDataRecords = 2 * minDataRecords ;
            // int rSize = RecordBufferPage.HEADER+(maxRecords*params.getRecordLength()) ;
            blkSize = RecordBufferPage.calcBlockSize(params.getRecordFactory(), maxDataRecords) ;
        } else
            blkSize = params.getCalcBlockSize() ;
    
        // By FileSet
        BufferChannel chan = BufferChannelMem.create(name+"(root)") ; 
        BlockMgr mgr1 = BlockMgrFactory.createMem(name + "(nodes)", params.getCalcBlockSize()) ;
        BlockMgr mgr2 = BlockMgrFactory.createMem(name + "(records)", blkSize) ;
        ComponentId cid = ComponentId.allocLocal() ;
        BPlusTree bpTree = BPlusTreeFactory.create(cid, params, chan, mgr1, mgr2) ;
        return bpTree ;
    }

    /** Debugging */
    public static BPlusTree addTracking(BPlusTree bpTree) {
        BufferChannel mgrRoot = null ;
        BlockMgr mgr1 = bpTree.getNodeManager().getBlockMgr() ;
        BlockMgr mgr2 = bpTree.getRecordsMgr().getBlockMgr() ;
        mgr1 = BlockTracker.track(mgr1) ;
        mgr2 = BlockTracker.track(mgr2) ;
        return BPlusTreeFactory.rebuild(bpTree, mgrRoot, mgr1, mgr2) ;
    }
    
    /** Debugging */
    public static BPlusTree addLogging(BPlusTree bpTree) {
        BufferChannel mgrRoot = null ;
        BlockMgr mgr1 = bpTree.getNodeManager().getBlockMgr() ;
        BlockMgr mgr2 = bpTree.getRecordsMgr().getBlockMgr() ;
        mgr1 = new BlockMgrLogger(mgr1, false) ; 
        mgr2 = new BlockMgrLogger(mgr2, false) ; 
        return BPlusTreeFactory.rebuild(bpTree, mgrRoot, mgr1, mgr2) ;
    }
    
    /** Create if does not exist */ 
    private static int createIfAbsent(boolean isReset, BPTStateMgr stateMgr, BPTreeNodeMgr nodeManager, BPTreeRecordsMgr recordsMgr) {
        
        int rootId = stateMgr.getRoot() ; 
        
        if ( nodeManager.getBlockMgr().isEmpty() != recordsMgr.getBlockMgr().isEmpty() )
            throw new BPTreeException(
                "Node block manager empty = "+nodeManager.getBlockMgr().isEmpty()+
                " // "+
                "Records block manager empty = "+recordsMgr.getBlockMgr().isEmpty()) ;
        
        if ( ! nodeManager.getBlockMgr().isEmpty() ) {
            return rootId ;
        } 
//        else {
//            if ( isReset )
//                throw new BPTreeException("Reset on uninitialized B+Tree") ;
//        }

        // Create/format

        // Fresh BPlusTree root node.
        int rootIdx = createEmptyBPT(stateMgr, nodeManager, recordsMgr) ;
        if ( rootIdx != 0 )
            throw new InternalError() ;

        // Sync created blocks to disk - any caches are now clean.
        stateMgr.sync();
        nodeManager.getBlockMgr().sync() ;
        recordsMgr.getBlockMgr().sync() ;
        return rootIdx ;
    }

    /** Allocate root node space. The root is a Node with a Records block.
     * @param stateMgr */ 
    private static int createEmptyBPT(BPTStateMgr stateMgr, BPTreeNodeMgr nodeManager, BPTreeRecordsMgr recordsMgr) { 
        // Create an empty records block.
        
        nodeManager.startUpdate();
        recordsMgr.startUpdate();
        // Empty tree.
        stateMgr.setState(0, 0, 0); 
        try {
            BPTreeRecords recordsPage = recordsMgr.create() ;
            if ( recordsPage.getId() != BPlusTreeParams.RootId )
                throw new DBOpEnvException("Root blocks must be at position zero (got "+recordsPage.getId()+")") ;
            // Empty data block.
            recordsMgr.write(recordsPage) ;
            recordsMgr.release(recordsPage) ;

            // Not this - we haven't full initialized and the BPTreeRecords has null BPTreeRecordsMgr
            //recordsPage.write();
            //recordsPage.release() ;

            BPTreeNode n = nodeManager.createNode(BPlusTreeParams.RootParent) ;
            // n.ptrs is currently invalid.  count was 0 so thinks it has a pointer.
            // Force to right layout.
            n.ptrs.setSize(0) ;                 // No pointers
            n.ptrs.add(recordsPage.getId()) ;   // Add the page below

            //n.ptrs.set(0, page.getId()) ; // This is the same as the size is one.

            n.setIsLeaf(true) ;
            n.setCount(0) ;     // Count is count of records.
            int rootId = n.getId()  ;
            nodeManager.write(n) ;
            nodeManager.release(n) ;
            // This makes the next blocks alocated 1 and 1 - just like a transaction.
            // Blocks 0/0 are always an empty tree unless the bulk loader built the tree.
            stateMgr.setState(0, 1, 1) ;
            return rootId ;
        } finally {
            recordsMgr.finishUpdate();
            nodeManager.finishUpdate();
        }
        // stateMgr.setState(0, 1, 1);
    }
}

