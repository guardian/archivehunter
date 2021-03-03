import React, {useState, useEffect} from "react";
import {RouteComponentProps} from "react-router";
import {makeStyles, Snackbar} from "@material-ui/core";
import BrowseSortOrder from "./BrowseSortOrder";
import {AdvancedSearchDoc, ArchiveEntry, CollectionNamesResponse, SortableField, SortOrder} from "../types";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";
import MuiAlert from "@material-ui/lab/Alert";
import NewTreeView from "./NewTreeView";
import NewSearchComponent from "../common/NewSearchComponent";
import BrowsePathSummary from "./BrowsePathSummary";
import EntryDetails from "../Entry/EntryDetails";
import BoxSizing from "../common/BoxSizing";
import {urlParamsFromSearch} from "../common/UrlPathHelpers";
import Helmet from "react-helmet";

const useStyles = makeStyles({
    browserWindow: {
        display: "grid",
        gridTemplateColumns: "repeat(20, 5%)",
        gridTemplateRows: "[top] 200px [info-area] auto [bottom]",
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
    sortOrderSelector: {
        gridColumnStart: 4,
        gridColumnEnd: 6,
        gridRowStart: "top",
        gridRowEnd: "info-area",
        padding: "1em",
        overflow: "hidden"
    },
    summaryInfoArea: {
        gridColumnStart:6,
        gridColumnEnd: -4,
        gridRowStart: "top",
        gridRowEnd: "info-area",
        padding: "1em",
        overflow: "hidden"
    },
    searchResultsArea: {
        gridColumnStart: 4,
        gridColumnEnd: -4,
        gridRowStart: "info-area",
        gridRowEnd: "bottom",
        overflowX: "hidden",
        overflowY: "auto",
        marginLeft: "auto",
        marginRight: "auto"
    },
    detailsArea: {
        gridColumnStart: -4,
        gridColumnEnd: -1,
        gridRowStart: "top",
        gridRowEnd: "bottom",
        overflow: "auto",
    }
});

const NewBrowseComponent:React.FC<RouteComponentProps> = (props) => {
    const [sortOrder, setSortOrder] = useState<SortOrder>("Descending");
    const [sortField, setSortField] = useState<SortableField>("last_modified");
    const [lastError, setLastError] = useState<string|undefined>(undefined);
    const [showingAlert, setShowingAlert] = useState(false);
    const [collectionNames, setCollectionNames] = useState<string[]>([]);
    const [currentCollection, setCurrentCollection] = useState("");
    const [currentPath, setCurrentPath] = useState("");
    const [reloadCounter, setReloadCounter] = useState(0);
    const [searchDoc, setSearchDoc] = useState<AdvancedSearchDoc>({collection:""});
    const [pageSize, setPageSize] = useState(100);
    const [itemLimit, setItemLimit] = useState(200);
    const [newlyLightboxed, setNewlyLightboxed] = useState<string[]>([]);
    const [selectedEntry, setSelectedEntry] = useState<ArchiveEntry|undefined>(undefined);
    const [loading, setLoading] = useState(false);

    const [urlRequestedItem, setUrlRequestedItem] = useState<string|undefined>(undefined);

    const [leftDividerPos, setLeftDividerPos] = useState(4);
    const [rightDividerPos, setRightDividerPos] = useState(-4);

    const classes = useStyles();

    const refreshCollectionNames = async () => {
        try {
            const result = await axios.get<CollectionNamesResponse>("/api/browse/collections");
            setCollectionNames(result.data.entries);
            if(currentCollection=="" && result.data.entries.length>0) setCurrentCollection(result.data.entries[0]);
        } catch (err) {
            console.error("Could not refresh collection names: ", err);
            setLastError(formatError(err, false));
            setShowingAlert(true);
        }
    }

    /**
     * load in collection names at startup
     */
    useEffect(()=>{
        refreshCollectionNames();
    }, []);

    const idXtractor = /^([^:]+):(.*)$/;

    /**
     * decode an incoming item id to extract the collection and path parts
     * @param itemId item id to decode
     * @returns undefined if the id is not valid, or a 2-element array consisting of (collection, path)
     */
    const decodeIncomingItemId = (itemId:string) => {
        try {
            const decoded = atob(itemId);
            const matches = idXtractor.exec(decoded);

            if (matches) {
                return [matches[1], matches[2]]
            } else {
                return undefined
            }
        } catch(err) {
            console.error("Could not decode incoming string: ", err);
            return undefined;
        }
    }

    /**
     * if an item is specified on the url then open it.
     * we can only do this once the collections have been loaded in
     */
    useEffect(()=>{
        const urlParams = urlParamsFromSearch(props.location.search);

        if(urlParams.hasOwnProperty("open")) {
            console.log("Requested to open file id ", urlParams.open);
            const maybeDecoded = decodeIncomingItemId(urlParams.open);
            if(maybeDecoded) {
                console.log("Changing collection to ", maybeDecoded[0]);
                setCurrentCollection(maybeDecoded[0]);
                setUrlRequestedItem(urlParams.open);
            } else {
                console.error("The given item ID was not valid");
                setLastError("The given item ID was not valid");
                setShowingAlert(true);
            }

        }

    }, [collectionNames]);

    const stripTrailingSlash = (from:string)=> from.endsWith("/") ? from.slice(0,from.length-1) : from;

    /**
     * update search doc if collection name, path or reload counter changes
     */
    useEffect(()=>{
        const initialDoc:AdvancedSearchDoc = {
            collection: currentCollection,
            sortBy: sortField,
            sortOrder: sortOrder
        };

        const docWithPath = currentPath=="" ? initialDoc : Object.assign(initialDoc, {path: stripTrailingSlash(currentPath)});

        setSearchDoc(docWithPath);
    }, [currentCollection, reloadCounter, currentPath, sortField, sortOrder]);

    // const pathOnlyRegex = new RegExp("/[^/]*$");
    //  commented out, this makes clicking on an entry lose everything else that is not in its dirs
    // /**
    //  * make sure that the path is open if an item is selected
    //  */
    // useEffect(()=>{
    //     if(selectedEntry && currentPath!=selectedEntry.path) {
    //         const pathOnly = selectedEntry.path.replace(pathOnlyRegex, "");
    //         console.log("setting path to ", pathOnly);
    //         setCurrentPath(pathOnly);
    //     }
    //     if(selectedEntry && currentCollection!=selectedEntry.bucket) {
    //         setCurrentCollection(selectedEntry.bucket)
    //     }
    // }, [selectedEntry]);

    useEffect(()=>{
        if(selectedEntry) {
            setUrlRequestedItem(selectedEntry.id);
        }
    }, [selectedEntry]);

    useEffect(()=>{
        if(urlRequestedItem) {
            props.history.push(`?open=${encodeURIComponent(urlRequestedItem)}`);
        } else {
            props.history.push("?");
        }
    }, [urlRequestedItem]);

    const showComponentError = (errString:string)=>{
        setLastError(errString);
        setShowingAlert(true);
    }

    const closeAlert = () => setShowingAlert(false);

    /**
     * callback for NewSearchcomponent, this is called whenever a data load is completed.
     * if we have an item to open provided on the url, then select it IF it appears in the search results
     * @param loadedData array of ArchiveEntry that were loaded
     */
    const loadingDidComplete = (loadedData:ArchiveEntry[])=>{
        if(urlRequestedItem) {
            const maybeItemToSelect = loadedData.filter(entry=>entry.id==urlRequestedItem);
            if(maybeItemToSelect.length>0) {
                setSelectedEntry(maybeItemToSelect[0])
            } else {
                console.log("could not open item ", urlRequestedItem, " because it is not in the returned results")
            }
        }
        setLoading(false)
    }

    return <div className={classes.browserWindow}>
        <Helmet>
            <title>{
                selectedEntry ? `${selectedEntry.path} - Archive Hunter` : "Archive Hunter"
            }</title>
        </Helmet>
        <Snackbar open={showingAlert} onClose={closeAlert} autoHideDuration={8000}>
            <MuiAlert severity="error" onClose={closeAlert}>{lastError}</MuiAlert>
        </Snackbar>
        <div className={classes.sortOrderSelector} style={{gridColumnStart: leftDividerPos, gridColumnEnd: leftDividerPos+2}}>
            <BrowseSortOrder sortOrder={sortOrder}
                             field={sortField}
                             orderChanged={(newOrder)=>setSortOrder(newOrder)}
                             fieldChanged={(newField)=>setSortField(newField)}/>
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
        <div className={classes.summaryInfoArea} style={{gridColumnStart: leftDividerPos+2, gridColumnEnd: rightDividerPos}}>
            <BrowsePathSummary collectionName={currentCollection}
                               searchDoc={searchDoc}
                               path={currentPath}
                               parentIsLoading={loading}
                               refreshCb={()=>setReloadCounter(prevState => prevState+1)}
                               goToRootCb={()=>setCurrentPath("")}
                               showDotFiles={false}
                               showDotFilesUpdated={()=>{}}
                               onError={showComponentError}
                               />
        </div>
        <div className={classes.searchResultsArea}  style={{gridColumnStart: leftDividerPos, gridColumnEnd: rightDividerPos}}>
            <NewSearchComponent pageSize={pageSize}
                                itemLimit={itemLimit}
                                newlyLightboxed={newlyLightboxed}
                                onEntryClicked={(entry)=>setSelectedEntry(entry)}
                                selectedEntry={selectedEntry}
                                onErrorOccurred={showComponentError}
                                advancedSearch={searchDoc}
                                onLoadingStarted={()=>setLoading(true)}
                                onLoadingFinished={loadingDidComplete}
            />
        </div>
        <div className={classes.detailsArea} style={{gridColumnStart: rightDividerPos}}>
            <BoxSizing justify="left"
                       onRightClicked={()=>setRightDividerPos((prev)=>prev+1)}
                       onLeftClicked={()=>setRightDividerPos((prev)=>prev-1)}
            />
            <EntryDetails entry={selectedEntry}
                          autoPlay={true}
                          showJobs={true}
                          loadJobs={false}
                          onError={(message:string)=>{
                              setLastError(message);
                              setShowingAlert(true);
                          }}
                //when the user adds to lightbox we record it here. This state var is bound to the NewSearchComponent
                //which will then re-load data for the given entry (after a short delay)
                          lightboxedCb={(entryId:string)=>setNewlyLightboxed((prevState) => prevState.concat(entryId))}
            />
        </div>
    </div>
}

export default NewBrowseComponent;