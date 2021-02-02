import React, {useState, useEffect} from "react";
import {RouteComponentProps} from "react-router";
import {CircularProgress, FormControlLabel, Grid, Input, makeStyles, Snackbar} from "@material-ui/core";
import {Search} from "@material-ui/icons";
import MuiAlert from "@material-ui/lab/Alert";
import NewSearchComponent from "../common/NewSearchComponent";
import {ArchiveEntry} from "../types";
import EntryDetails from "../Entry/EntryDetails";
import axios from "axios";

const useStyles = makeStyles({
    searchBox: {
        marginLeft: "auto",
        marginRight: "auto",
        width: "60vw",
        alignItems: "flex-start"
    },
    spinner: {
        width: "16px",
        height: "16px"
    },
    appArea: {
        display: "grid",
        gridTemplateColumns: "repeat(20, 5%)",
        marginTop: "2em",
        height: "90vh"
    }
});

const NewBasicSearch:React.FC<RouteComponentProps> = (props) => {
    const classes = useStyles();
    const [typedSearch, setTypedSearch] = useState("");     //this state var stores the text as it is typed
    const [activeSearch, setActiveSearch] = useState("");   //this state var is shared with searchComponent as the basicSearch param
    const [showingAlert, setShowingAlert] = useState(false);
    const [lastError, setLastError] = useState<string|undefined>(undefined);
    const [selectedEntry, setSelectedEntry] = useState<ArchiveEntry|undefined>(undefined);
    const [isLoading, setIsLoading] = useState(false);
    const [dividerLocation, setDividerLocation] = useState(20);
    const [newlyLightboxedList, setNewlyLightboxedList] = useState<string[]>([]);

    const closeAlert = ()=>setShowingAlert(false);

    /**
     * 1 second after the user finishes typing, update the search parameter to trigger the search
     */
    useEffect(()=>{
        const timerId = window.setTimeout(()=>setActiveSearch(typedSearch), 1000);

        return ()=>{
            console.log(`cancelling previous timer ${timerId}`);
            window.clearTimeout(timerId);
        }
    }, [typedSearch]);

    /**
     * if the user has selected an entry, then open the details panel; if s/he has deselected then close it.
     */
    useEffect(()=>{
        if(selectedEntry) {
            setDividerLocation(16); //panel open
        } else {
            setDividerLocation(20); //panel closed
        }
    }, [selectedEntry]);

    return <>
        <Snackbar open={showingAlert} onClose={closeAlert} autoHideDuration={8000}>
            <MuiAlert severity="error" onClose={closeAlert}>{lastError}</MuiAlert>
        </Snackbar>
        <Grid container className={classes.searchBox} spacing={2}>
            <Grid item><Search/></Grid>
            <Grid item style={{flexGrow: 1}}>
                <Input onChange={(evt)=>setTypedSearch(evt.target.value)}
                       value={typedSearch}
                       style={{width: "100%"}}/>
            </Grid>
            {
                isLoading ? <Grid item className={classes.spinner}><CircularProgress/></Grid> : null
            }
        </Grid>
        <div className={classes.appArea}>

            <div style={{ gridColumnStart: 1, gridColumnEnd: dividerLocation}}>
                <NewSearchComponent pageSize={25}
                                    itemLimit={100}
                                    onEntryClicked={(entry)=>setSelectedEntry(entry)}
                                    onErrorOccurred={(error)=>{
                                        setLastError(error);
                                        setShowingAlert(true);
                                    }}
                                    basicQuery={activeSearch}
                                    selectedEntry={selectedEntry}
                                    onLoadingStarted={()=>setIsLoading(true)}
                                    onLoadingFinished={()=>setIsLoading(false)}
                                    newlyLightboxed={newlyLightboxedList}
                />
            </div>
            <div style={{gridColumnStart: dividerLocation, gridColumnEnd: -1}}>
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
                              lightboxedCb={(entryId:string)=>setNewlyLightboxedList((prevState) => prevState.concat(entryId))}
                />
            </div>
        </div>
    </>
}

export default NewBasicSearch;