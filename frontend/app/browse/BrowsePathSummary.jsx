import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import BytesFormatter from "../common/BytesFormatter.jsx";
import RefreshButton from "../common/RefreshButton";
import BulkLightboxAdd from "./BulkLightboxAdd.jsx";
import {CircularProgress, createStyles, Grid, IconButton, Typography, withStyles} from "@material-ui/core";
import {baseStyles} from "../BaseStyles";
import {FolderRounded, HomeOutlined, HomeRounded, Storage} from "@material-ui/icons";
import {formatError} from "../common/ErrorViewComponent";

const styles=Object.assign(createStyles({
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
        if(this.state.hasLoaded) return <div className="browse-path-summary">
            <Grid container direction="column" alignContent="center" spacing={1}>
                <Grid item className={this.props.classes.centered}>

                </Grid>

                {
                    <Grid item className={this.props.classes.centered}>
                        <Grid container direction="row"  justify="center" alignContent="space-around">
                            {this.props.path ? <>
                                <Grid item><IconButton
                                    onClick={this.props.goToRootCb}><HomeRounded/></IconButton></Grid>
                                <Grid item className={this.props.classes.summaryBoxElement}>
                                    <FolderRounded className={this.props.classes.summaryIcon}/>
                                    <Typography style={{display: "inline"}}>{this.props.path}</Typography>
                                </Grid>
                            </> : null
                            }
                            <Grid item className={this.props.classes.summaryBoxElement}>
                                <Typography className={this.props.classes.collectionNameText}>
                                    <Storage className={this.props.classes.summaryIcon}/>
                                    {this.props.collectionName}
                                </Typography>
                            </Grid>
                        </Grid>
                    </Grid>
                }

                <Grid item className={this.props.classes.centered}>
                    <BulkLightboxAdd path={this.props.path}
                                     hideDotFiles={! this.props.showDotFiles}
                                     collection={this.props.collectionName}
                                     searchDoc={this.props.searchDoc}
                                     onError={this.props.onError}
                    />
                </Grid>
            </Grid>

            <div>
                <Typography>
                    <RefreshButton isRunning={this.props.parentIsLoading}
                                   clickedCb={this.props.refreshCb}/>
                                   Total of {this.state.totalHits} items occupying <BytesFormatter value={this.state.totalSize}/>
                </Typography>
            </div>

            {
                this.state.deletedCounts.hasOwnProperty("1") ? <Typography>
                    {this.state.deletedCounts["1"]} tracked items have been deleted
                </Typography> : null
            }
        </div>;

        return <div><i>not loaded</i></div>
    }
}

export default withStyles(styles)(BrowsePathSummary);