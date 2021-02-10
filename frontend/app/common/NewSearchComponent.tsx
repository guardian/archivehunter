import React, {useState, useEffect} from "react";
import {AdvancedSearchDoc, ArchiveEntry, ArchiveEntryResponse, SearchResponse} from "../types";
import axios, {AxiosResponse, CancelToken} from "axios";
import {formatError} from "./ErrorViewComponent";
import {Grid, makeStyles, Typography} from "@material-ui/core";
import EntryView from "../search/EntryView";

interface NewSearchComponentProps {
    advancedSearch?: AdvancedSearchDoc; //if advancedSearch is set, then it is preferred over basicQuery
    basicQuery?: string;                //query string to use if advancedSearch is not set
    basicQueryUrl?: string;             //supply a full URL for the query if advancedSearch is not set. This overrides basicQuery
    pageSize: number;
    itemLimit: number;
    selectedEntry?: ArchiveEntry;
    newlyLightboxed: string[];          //array of item IDs that have been added to lightbox
    onEntryClicked: (entry:ArchiveEntry)=>void;         //called when an entry is clicked
    onErrorOccurred: (errorDescription:string)=>void;   //called when a load error occurs, parent should display the error
    onLoadingStarted?: ()=>void;
    onLoadingFinished?: (loadedData:ArchiveEntry[])=>void;
}

const useStyles = makeStyles({
    searchResultsContainer: {
        overflowY: "auto",
        overflowX: "hidden",
        height: "100%",
        marginLeft: "1.8em",
        marginRight: "1.8em"
    },
    centeredText: {
        marginLeft: "auto",
        marginRight: "auto"
    }
});

/**
 * Replacement for SearchComponent and SearchManager.  Encapsulates frontend search request and display logic.
 * @param props
 * @constructor
 */
const NewSearchComponent:React.FC<NewSearchComponentProps> = (props) => {
    const [entries, setEntries] = useState<ArchiveEntry[]>([]);
    const [loadingInProgress, setLoadingInProgress] = useState(false);

    const [cancelToken, setCancelToken] = useState<CancelToken|undefined>(undefined);
    const classes = useStyles();

    const makeSearchRequest = (token:CancelToken, startAt: number):Promise<AxiosResponse<SearchResponse>> => {
        if(props.advancedSearch) {
            return axios.post<SearchResponse>(`/api/search/browser?start=${startAt}&size=${props.pageSize}`, props.advancedSearch, {
                cancelToken: token
            })
        } else if(props.basicQueryUrl) {
            //if we already have a query string then append, if we do not then start one
            const separator = props.basicQueryUrl.includes("?") ? "&" : "?";
            return axios.get<SearchResponse>(props.basicQueryUrl + `${separator}start=${startAt}&length=${props.pageSize}`, {
                cancelToken: token
            })
        } else if(props.basicQuery) {
            return axios.get<SearchResponse>("/api/search/basic?q=" + encodeURIComponent(props.basicQuery as string) + "&start=" + startAt + "&length=" + props.pageSize,
                {
                    cancelToken: token
                });
        } else {
            return new Promise((resolve, reject)=>reject("No query to make"));
        }
    }

    /**
     * recursively load in pages from the server.  This will recurse until either all results are loaded or
     * props.itemLimit is hit.
     * @param token cancelToken that will stop the operation.  If the cancelToken is activated, then the `entries` state
     * var will be blanked.
     * @param startAt item number to start at on the server, normally 0.
     */
    const loadNextPage:(token:CancelToken, startAt:number)=>Promise<boolean> = async (token:CancelToken, startAt: number)=>{
        try {
            if(startAt+props.pageSize>props.itemLimit) {
                console.log(`Hit the item limit of ${props.itemLimit}, stopping`);
                return true;
            }
            const results = await makeSearchRequest(token, startAt);

            if(results.data.entries.length>0) {
                console.log(`Received ${results.data.entries.length} results, loading next page...`)
                setEntries((prev)=>prev.concat(results.data.entries));
                return loadNextPage(token, startAt+results.data.entries.length);
            } else {
                console.log("Received 0 results, assuming got to the end of iteration");
                setLoadingInProgress(false);
                return true;
            }
        } catch(err) {
            if(axios.isCancel(err)) {
                console.log("search was cancelled, clearing results");
                setEntries([]);
            } else if(err=="No query to make") {

            } else {
                props.onErrorOccurred(formatError(err, false));
            }
            setLoadingInProgress(false);

            return false;
        }
    }


    useEffect(()=>{
        if(!loadingInProgress && props.onLoadingFinished) {
            props.onLoadingFinished(entries);
        }
    }, [loadingInProgress, entries]);

    const indexForFileid = (entryId:string)=>{
        for(let i=0;i<entries.length;++i){
            console.debug("checking "+entries[i].id+ "against" + entryId);
            if(entries[i].id===entryId) return i;
        }
        console.error("Could not find existing entry for id " + entryId);
        return -1;
    }

    /**
     * used to update a specific entry in the list from new data
     * @param newEntry the new data to replace
     * @param atIndex index to replace it at
     * @param entryId the id of the new entry
     */
    const updateSearchResults = (newEntry:ArchiveEntry, atIndex: number) => {
        setEntries((prevState)=>prevState.slice(0, atIndex).concat([newEntry].concat(prevState.slice(atIndex + 1))));
    }

    /**
     * updates the search view data for a specific item once it has been added to the lightbox.
     * Returns a Promise that resolves once the operation is fully completed
     * @param entryId entry to update
     * @returns {Promise} Promise that resolves once all updates have been done
     */
    useEffect(()=>{
        const updateNewlyLightboxed = async ()=> {
            const promises = props.newlyLightboxed.map((entryId,idx)=>
                axios.get<ArchiveEntryResponse>(`/api/entry/${entryId}`)
            );

            try {
                const results = await axios.all(promises);
                results.forEach((result) => {
                    const entryIndex = indexForFileid(result.data.entry.id);
                    updateSearchResults(result.data.entry, entryIndex);
                    if(result.data.entry.id===props.selectedEntry?.id) {
                        props.onEntryClicked(result.data.entry)
                    }
                })
            } catch(err) {
                console.error("could not update lightboxed entries: ", err);
                props.onErrorOccurred(formatError(err, false));
            }
        }

        const timerId = window.setTimeout(()=>updateNewlyLightboxed(), 1000);

        return ()=> {
            //if we update again before the timeout is completed, then cancel the un-necessary old one and re-wait
            window.clearTimeout(timerId);
        }

    }, [props.newlyLightboxed]);

    /**
     * when the search parameters change, then load in new data
     */
    useEffect(()=>{
        const cancelTokenFactory = axios.CancelToken.source();
        setCancelToken(cancelTokenFactory.token);

        if(props.onLoadingStarted) props.onLoadingStarted();
        setLoadingInProgress(true);
        setEntries([]);
        loadNextPage(cancelTokenFactory.token, 0);
        return ()=>{
            cancelTokenFactory.cancel("search interrupted");
        }
    }, [props.advancedSearch, props.basicQuery, props.basicQueryUrl]);

    return <Grid container className={classes.searchResultsContainer}>
        {
            entries.map((entry,idx)=><EntryView
                                                key={idx}
                                                isSelected={ props.selectedEntry ? props.selectedEntry.id===entry.id : false}
                                                entry={entry}
                                                cancelToken={cancelToken}
                                                itemOpenRequest={props.onEntryClicked}
            />)
        }
        {
            entries.length==0 ? <Typography variant="h3" className={classes.centeredText}>No results</Typography> : null
        }
    </Grid>
}

export default NewSearchComponent;