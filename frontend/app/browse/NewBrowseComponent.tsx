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
        overflow: "hidden"
    },
    sortOrderSelector: {
        gridColumnStart: -4,
        gridColumnEnd: -3,
        gridRowStart: "top",
        gridRowEnd: "info-area",
        padding: "1em"
    },
    summaryInfoArea: {
        gridColumnStart:4,
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
    const [searchDoc, setSearchDoc] = useState<AdvancedSearchDoc|undefined>(undefined);
    const [pageSize, setPageSize] = useState(100);
    const [itemLimit, setItemLimit] = useState(200);
    const [newlyLightboxed, setNewlyLightboxed] = useState<string[]>([]);
    const [selectedEntry, setSelectedEntry] = useState<ArchiveEntry|undefined>(undefined);
    const [loading, setLoading] = useState(false);

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
        refreshCollectionNames()
    }, []);

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

        setSearchDoc((prevState)=>
            prevState ? Object.assign({}, prevState, docWithPath) : docWithPath
        );
    }, [currentCollection, reloadCounter, currentPath, sortField, sortOrder]);

    const showComponentError = (errString:string)=>{
        setLastError(errString);
        setShowingAlert(true);
    }

    const closeAlert = () => setShowingAlert(false);

    return <div className={classes.browserWindow}>
        <Snackbar open={showingAlert} onClose={closeAlert} autoHideDuration={8000}>
            <MuiAlert severity="error" onClose={closeAlert}>{lastError}</MuiAlert>
        </Snackbar>
        <div className={classes.sortOrderSelector}>
            <BrowseSortOrder sortOrder={sortOrder}
                             field={sortField}
                             orderChanged={(newOrder)=>setSortOrder(newOrder)}
                             fieldChanged={(newField)=>setSortField(newField)}/>
        </div>
        <div className={classes.pathSelector}>
            <NewTreeView currentCollection={currentCollection}
                         collectionList={collectionNames}
                         collectionDidChange={(newCollection)=>setCurrentCollection(newCollection)}
                         pathSelectionChanged={(newpath)=>setCurrentPath(newpath)}
                         onError={showComponentError}/>
        </div>
        <div className={classes.searchResultsArea}>
            <NewSearchComponent pageSize={pageSize}
                                itemLimit={itemLimit}
                                newlyLightboxed={newlyLightboxed}
                                onEntryClicked={(entry)=>setSelectedEntry(entry)}
                                selectedEntry={selectedEntry}
                                onErrorOccurred={showComponentError}
                                advancedSearch={searchDoc}
                                onLoadingStarted={()=>setLoading(true)}
                                onLoadingFinished={()=>setLoading(false)}
            />
        </div>
    </div>
}

export default NewBrowseComponent;