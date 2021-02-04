import React, {useState, useEffect} from "react";
import {RouteComponentProps} from "react-router";
import {makeStyles, Snackbar} from "@material-ui/core";
import BrowseSortOrder from "./BrowseSortOrder";
import {AdvancedSearchDoc, CollectionNamesResponse, SortableField, SortOrder} from "../types";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";
import MuiAlert from "@material-ui/lab/Alert";
import NewTreeView from "./NewTreeView";

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

    /**
     * reload if collection name changes
     */
    useEffect(()=>{
        setSearchDoc((prevState)=>
            prevState ? Object.assign({}, prevState, {collection: currentCollection}) : {collection:currentCollection}
        );
    }, [currentCollection]);

    /**
     * update search doc if collection name, path or reload counter changes
     */
    useEffect(()=>{

    }, [currentCollection, reloadCounter, currentPath]);

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
                         onError={(errString)=>{
                             setLastError(errString);
                             setShowingAlert(true);
                         }}/>
        </div>
    </div>
}

export default NewBrowseComponent;