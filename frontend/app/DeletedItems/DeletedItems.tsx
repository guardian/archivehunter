import React, {useState, useEffect} from "react";
import {RouteComponentProps} from "react-router";
import {Helmet} from "react-helmet";
import AdminContainer from "../admin/AdminContainer";
import {makeStyles, Snackbar} from "@material-ui/core";
import BoxSizing from "../common/BoxSizing";
import NewTreeView from "../browse/NewTreeView";
import axios from "axios";
import {AdvancedSearchDoc, CollectionNamesResponse} from "../types";
import {formatError} from "../common/ErrorViewComponent";
import MuiAlert from "@material-ui/lab/Alert";
import BrowsePathSummary from "../browse/BrowsePathSummary";

const useStyles = makeStyles((theme)=>({
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
    summaryInfoArea: {
        gridColumnStart:6,
        gridColumnEnd: -4,
        gridRowStart: "top",
        gridRowEnd: "info-area",
        padding: "1em",
        overflow: "hidden"
    },
}));

const DeletedItemsComponent:React.FC<RouteComponentProps> = (props) => {
    const [currentCollection, setCurrentCollection] = useState("");
    const [collectionNames, setCollectionNames] = useState<string[]>([]);
    const [currentPath, setCurrentPath] = useState<string|undefined>(undefined);
    const [reloadCounter, setReloadCounter] = useState(0);
    const [loading, setLoading] = useState(true);
    const [searchDoc, setSearchDoc] = useState<AdvancedSearchDoc>({collection:""});

    const [lastError, setLastError] = useState<string|undefined>(undefined);
    const [showingAlert, setShowingAlert] = useState(false);

    const [leftDividerPos, setLeftDividerPos] = useState(4);

    const classes = useStyles();

    useEffect(()=>{
        refreshCollectionNames().then(()=>{
            setLoading(false);
        })
    }, [])

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

    const showComponentError = (errString:string)=>{
        setLastError(errString);
        setShowingAlert(true);
    }

    const closeAlert = () => setShowingAlert(false);

    return <>
        <Helmet>
            <title>Deleted items {currentCollection ? `in ${currentCollection}` : ""} - ArchiveHunter</title>
        </Helmet>
        <Snackbar open={showingAlert} onClose={closeAlert} autoHideDuration={8000}>
            <MuiAlert severity="error" onClose={closeAlert}>{lastError}</MuiAlert>
        </Snackbar>
        <AdminContainer {...props}>
            <div className={classes.browserWindow}>
                <div className={classes.summaryInfoArea} style={{gridColumnStart: leftDividerPos+2, gridColumnEnd: -1}}>
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
            </div>
        </AdminContainer>
        </>
}

export default DeletedItemsComponent;