import React, {useState, useEffect, useRef} from "react";
import {RouteComponentProps} from "react-router";
import {Helmet} from "react-helmet";
import AdminContainer from "../admin/AdminContainer";
import {makeStyles, Snackbar} from "@material-ui/core";
import BoxSizing from "../common/BoxSizing";
import NewTreeView from "../browse/NewTreeView";
import axios from "axios";
import {AdvancedSearchDoc, ArchiveEntry, BulkDeleteConfirmationResponse, CollectionNamesResponse} from "../types";
import {formatError} from "../common/ErrorViewComponent";
import MuiAlert from "@material-ui/lab/Alert";
import BrowsePathSummary from "../browse/BrowsePathSummary";
import DeletedItemSummary from "./DeletedItemSummary";
import DeletedItemsTable from "./DeletedItemsTable";
import {loadDeletedItemStream} from "./DeletedItemsStreamConsumer";

const useStyles = makeStyles((theme)=>({
    browserWindow: {
        display: "grid",
        gridTemplateColumns: "repeat(20, 5%)",
        gridTemplateRows: "[top] 150px [info-area] auto [bottom]",
        height: "95vh"
    },
    pathSelector: {
        gridColumnStart: 1,
        gridColumnEnd: 4,
        gridRowStart: "top",
        gridRowEnd: "bottom",
        borderRight: "1px solid white",
        padding: "1em",
        overflowX: "hidden",
        overflowY: "auto",
    },
    summaryInfoArea: {
        gridColumnStart:6,
        gridColumnEnd: -4,
        gridRowStart: "top",
        gridRowEnd: "info-area",
        padding: "1em",
        overflow: "hidden"
    },
    dataView: {
        gridRowStart: "info-area",
        gridRowEnd: "bottom",
        padding: "1em",
        overflowX: "hidden",
        overflowY: "auto",
        height: "95%"
    }
}));

const DeletedItemsComponent:React.FC<RouteComponentProps> = (props) => {
    const [currentCollection, setCurrentCollection] = useState("");
    const [collectionNames, setCollectionNames] = useState<string[]>([]);
    const [currentPath, setCurrentPath] = useState<string|undefined>(undefined);
    const [reloadCounter, setReloadCounter] = useState(0);
    const [entries, setEntries] = useState<ArchiveEntry[]>([]);

    const [loading, setLoading] = useState(false);
    const [searchDoc, setSearchDoc] = useState<AdvancedSearchDoc>({collection:""});
    const [lastError, setLastError] = useState<string|undefined>(undefined);
    const [successMessage, setSuccessMessage] = useState<string|undefined>(undefined);
    const [showingAlert, setShowingAlert] = useState(false);
    const [leftDividerPos, setLeftDividerPos] = useState(4);

    const [awaitingStopLoading, setAwaitingStopLoading] = useState(false);
    const [awaitingRefresh, setAwaitingRefresh] = useState(false);

    const awaitingStopLoadingRef = useRef<boolean>();

    awaitingStopLoadingRef.current = awaitingStopLoading;   //in order to access the up-to-date state var in a callback we must use a mutable ref. See https://stackoverflow.com/questions/57847594/react-hooks-accessing-up-to-date-state-from-within-a-callback

    const classes = useStyles();

    useEffect(()=>{
        refreshCollectionNames();
    }, [])

    useEffect(()=>{
        setSearchDoc(Object.assign({}, searchDoc, {collection: currentCollection}))
    }, [currentCollection]);

    const loadFromFresh = ()=>{
        console.log("DeletedItemsTable loading in data");
        setEntries([]);
        setLoading(true);
        return loadDeletedItemStream(currentCollection, currentPath, searchDoc, receivedNewData, 50);
    }

    useEffect(()=>{
        console.log("searchDoc or currentPath changed, loading is ", loading, " awaitingStopLoading is ", awaitingStopLoading);
        if(loading) {
            setAwaitingStopLoading(true);
            setAwaitingRefresh(true);
        } else {
            loadFromFresh();
        }
    }, [searchDoc, currentPath]);

    /**
     * if awaitingStopLoading goes to false, then we should trigger a reload
     */
    useEffect(()=>{
        if(!awaitingStopLoading && awaitingRefresh) {
            setAwaitingRefresh(false);
            window.setTimeout(()=>{
                loadFromFresh();
            }, 1000);   //schedule a reload once we have terminated the current operation
        }
    }, [awaitingStopLoading]);

    const receivedNewData = (entry:ArchiveEntry|undefined, isDone:boolean) => {
        console.log("Got new entry: ", entry, " stream completing: ", isDone);
        if(entry) {
            setEntries((prev)=>prev.concat(entry));
        }
        if(isDone) {
            setAwaitingStopLoading(false);
            setLoading(false);
            return true;
        }

        if(awaitingStopLoadingRef.current) {
            console.log("Stopping due to reload...");
            setAwaitingStopLoading(false);
            setLoading(false);
            return false;
        } else {
            return true;
        }
    }

    const refreshCollectionNames = async () => {
        try {
            const result = await axios.get<CollectionNamesResponse>("/api/browse/collections");
            setCollectionNames(result.data.entries);
            setLoading(false);  //this must happen BEFORE we change the current collection, otherwise the data table won't update
            if(currentCollection=="" && result.data.entries.length>0) setCurrentCollection(result.data.entries[0]);
        } catch (err) {
            console.error("Could not refresh collection names: ", err);
            setLastError(formatError(err, false));
            setShowingAlert(true);
        }
    }

    const showComponentError = (errString:string)=>{
        setLastError(errString);
        setShowingAlert(true);
    }

    const closeAlert = () => {
        setSuccessMessage(undefined);
        setLastError(undefined);
        setShowingAlert(false);
    }

    const removalRequested = async (itemId:string)=> {
        console.log("Removal requested for ", itemId);
        try {
            await axios.delete(`/api/deleted/${encodeURIComponent(currentCollection)}/${encodeURIComponent(itemId)}`)
            setEntries((prev)=>prev.filter(entry=>entry.id!==itemId));
            setSuccessMessage("Removed tombstone");
            setShowingAlert(true);
        } catch(err) {
            console.error("Could not perform item deletion: ", err);
            setLastError(formatError(err, false));
            setShowingAlert(true);
        }
    }

    const removeAllRequested = async ()=> {
        console.log("Removal requested for everything here")
        let args = "";
        if(currentPath) {
            args = `?prefix=${encodeURIComponent(currentPath)}`;
        }

        try {
            const response = await axios.delete<BulkDeleteConfirmationResponse>(`/api/deleted/${encodeURIComponent(currentCollection)}${args}`, {
                data: searchDoc,
                headers: {
                    "Content-Type": "application/json"
                }
            });
            setSuccessMessage(`Removed ${response.data.deletedCount} tombstones in ${response.data.timeTaken} ms`);
            setShowingAlert(true);
            loadFromFresh();
        } catch(err) {
            console.error("Bulk removal failed: ", err);
            setLastError(formatError(err, false));
            setShowingAlert(true);
        }
    }

    return <>
        <Helmet>
            <title>Deleted items {currentCollection ? `in ${currentCollection}` : ""} - ArchiveHunter</title>
        </Helmet>
        <Snackbar open={showingAlert && (lastError!=undefined|| successMessage!=undefined)} onClose={closeAlert} autoHideDuration={8000}>
            <>
                <MuiAlert severity="error" onClose={closeAlert} style={{display: lastError ? "inherit" : "none"}}>{lastError}</MuiAlert>
                <MuiAlert severity="info" onClose={closeAlert} style={{display: successMessage ? "inherit" : "none"}}>{successMessage}</MuiAlert>
            </>
        </Snackbar>
        <AdminContainer {...props}>
            <div className={classes.browserWindow}>
                <div className={classes.summaryInfoArea} style={{gridColumnStart: leftDividerPos+2, gridColumnEnd: -1}}>
                    <DeletedItemSummary collectionName={currentCollection}
                                       searchDoc={searchDoc}
                                       path={currentPath}
                                       parentIsLoading={loading}
                                       refreshCb={()=>setReloadCounter(prevState => prevState+1)}
                                       goToRootCb={()=>setCurrentPath("")}
                                       requestRemoveAll={removeAllRequested}
                                       loadingNotifiction={`Loaded ${entries.length} items`}
                                       onError={showComponentError}
                    />

                </div>
                <div className={classes.pathSelector} style={{gridColumnEnd: leftDividerPos}}>
                    <BoxSizing justify="right"
                               onRightClicked={()=>setLeftDividerPos((prev)=>prev+1)}
                               onLeftClicked={()=>setLeftDividerPos((prev)=>prev-1)}
                    />
                    <NewTreeView currentCollection={currentCollection}
                                 collectionList={collectionNames}
                                 collectionDidChange={(newCollection)=>setCurrentCollection(newCollection)}
                                 pathSelectionChanged={(newpath)=>setCurrentPath(newpath)}
                                 onError={showComponentError}/>
                </div>
                <div className={classes.dataView} style={{gridColumnStart: leftDividerPos, gridColumnEnd: -1}}>
                    <DeletedItemsTable entries={entries}
                                       requestDelete={removalRequested}
                                       currentlyLoading={loading}
                                       requestOpen={(itemId)=>props.history.push(`/item/${encodeURIComponent(itemId)}`)}
                    />
                </div>
            </div>
        </AdminContainer>
        </>
}

export default DeletedItemsComponent;
