import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import BytesFormatter from "../common/BytesFormatter.jsx";
import RefreshButton from "../common/RefreshButton";
import BulkLightboxAdd from "./BulkLightboxAdd.jsx";
import {CircularProgress, createStyles, Grid, IconButton, Tooltip, Typography, withStyles} from "@material-ui/core";
import {baseStyles} from "../BaseStyles";
import {FolderRounded, HomeOutlined, HomeRounded, Storage, WarningRounded} from "@material-ui/icons";
import {formatError} from "../common/ErrorViewComponent";
import PathDisplayComponent from "./PathDisplayComponent";
import clsx from "clsx";
import BrowseSummaryDisplay from "./BrowseSummaryDisplay";

const styles=(theme)=>Object.assign(createStyles({
    summaryIcon: {
        marginRight: "0.1em",
        verticalAlign: "bottom"
    },
    collectionNameText: {
        fontWeight: "bold"
    },
    summaryBoxElement: {
        marginTop: "auto",
        marginBottom: "auto",
        marginLeft: "1em"
    },
    warningIcon: {
        color: theme.palette.warning.dark
    }
}), baseStyles);

/**
 * this component is the "top banner" for the Browse view, showing the collection, path within collection, filters etc.
 */
class BrowsePathSummary extends React.Component {
    static propTypes = {
        collectionName: PropTypes.string.isRequired,
        path: PropTypes.string,
        searchDoc: PropTypes.object.isRequired,
        parentIsLoading: PropTypes.bool.isRequired,
        refreshCb: PropTypes.func.isRequired,
        goToRootCb: PropTypes.func.isRequired,
        showDotFiles: PropTypes.bool.isRequired,
        showDotFilesUpdated:PropTypes.func.isRequired,
        queryString: PropTypes.string,
        onError: PropTypes.func
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            hasLoaded: false,
            advancedExpanded: false,

            //see PathInfoResponse.scala
            totalHits: -1,
            totalSize: -1,
            deletedCounts: {},
            proxiedCounts: {},
            typesCount: {}
        };

        this.toggleAdvancedExpanded = this.toggleAdvancedExpanded.bind(this);
    }

    refreshData(){
        if(!this.props.collectionName || this.props.collectionName==="") return;

        const pathToSearch = this.props.path ?
            this.props.path.endsWith("/") ? this.props.path.slice(0, this.props.path.length - 1) : this.props.path : null;

        const urlsuffix = pathToSearch ? "?prefix=" + encodeURIComponent(pathToSearch) : "";
        const url = "/api/browse/" + this.props.collectionName + "/summary" + urlsuffix;

        this.setState({loading:true, hasLoaded: false},
            ()=>axios.put(url, this.props.searchDoc, {headers: {"Content-Type": "application/json"}}).then(response=>{
                this.setState({
                    loading:false, hasLoaded:true, lastError:null,
                    totalHits: response.data.totalHits,
                    totalSize: response.data.totalSize,
                    deletedCounts: response.data.deletedCounts,
                    proxiedCounts: response.data.proxiedCounts,
                    typesCount: response.data.typesCount
                })
        }).catch(err=>{
            console.error("Could not refresh path summary data: ", err);
            if(this.props.onError) this.props.onError(formatError(err, false));
            this.setState({loading: false, hasLoaded: false})
        }))
    }

    componentDidMount(){
        this.refreshData();
    }

    componentDidUpdate(oldProps,oldState, snapshot){
        if(oldProps.collectionName!==this.props.collectionName || oldProps.path!==this.props.path) this.refreshData();
    }

    toggleAdvancedExpanded(value){
        this.setState({advancedExpanded: value})
    }

    render(){
        if(this.state.loading) return <div className="path-summary">
            <CircularProgress style={{marginRight: "2em"}}/>
            <Typography>Loading...</Typography>
        </div>;

        /*TODO: add in jschart and put a horizontal bar of the filetypes breakdown*/
        if(this.state.hasLoaded) return <BrowseSummaryDisplay
            collectionName={this.props.collectionName}
            path={this.props.path}
            goToRootCb={this.props.goToRootCb}
            parentIsLoading={this.props.parentIsLoading}
            refreshCb={this.props.refreshCb}
            totalHits={this.state.totalHits}
            totalSize={this.state.totalSize}
        >
            { this.state.deletedCounts.hasOwnProperty("1")  ? <Grid item>
                <Tooltip title={`${this.state.deletedCounts["1"]} tracked items have been deleted`}>
                    <WarningRounded className={this.props.classes.warningIcon}/>
                </Tooltip>
            </Grid> : null }

            <Grid item>
                <BulkLightboxAdd path={this.props.path}
                                 hideDotFiles={! this.props.showDotFiles}
                                 collection={this.props.collectionName}
                                 searchDoc={this.props.searchDoc}
                                 onError={this.props.onError}
                />
            </Grid>
        </BrowseSummaryDisplay>;

        return <div><i>not loaded</i></div>
    }
}

export default withStyles(styles)(BrowsePathSummary);