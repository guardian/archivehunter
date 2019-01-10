import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import BytesFormatter from "../common/BytesFormatter.jsx";
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import ReactRadioButtonGroup from 'react-radio-button-group';

/**
 * this component is the "top banner" for the Browse view, showing the collection, path within collection, filters etc.
 */
class BrowsePathSummary extends React.Component {
    static propTypes = {
        collectionName: PropTypes.string.isRequired,
        path: PropTypes.string,
        onSortChanged: PropTypes.func.isRequired, //this is called when sort field or sort order is changed. First arg is new sort field, second is new sort order.
        sortField: PropTypes.string.isRequired,
        sortOrder: PropTypes.string.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            hasLoaded: false,
            lastError: null,
            //see PathInfoResponse.scala
            totalHits: -1,
            totalSize: -1,
            deletedCounts: {},
            proxiedCounts: {},
            typesCount: {}
        };
        this.sortOrderChanged = this.sortOrderChanged.bind(this);
        this.sortFieldChanged = this.sortFieldChanged.bind(this);
    }

    refreshData(){
        if(!this.props.collectionName || this.props.collectionName==="") return;

        const pathToSearch = this.props.path ?
            this.props.path.endsWith("/") ? this.props.path.slice(0, this.props.path.length - 1) : this.props.path : null;

        const urlsuffix = pathToSearch ? "?prefix=" + encodeURIComponent(pathToSearch) : "";
        const url = "/api/browse/" + this.props.collectionName + "/summary" + urlsuffix;

        this.setState({loading:true, hasLoaded: false, lastError: null}, ()=>axios.get(url).then(response=>{
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
            this.setState({loading: false, hasLoaded: false, lastError: err})
        }))
    }

    componentWillMount(){
        this.refreshData();
    }

    componentDidUpdate(oldProps,oldState){
        if(oldProps.collectionName!==this.props.collectionName || oldProps.path!==this.props.path) this.refreshData();
    }

    sortFieldChanged(evt) {
        this.props.onSortChanged(evt.target.value, this.props.sortOrder);
    }

    sortOrderChanged(newValue) {
        this.props.onSortChanged(this.props.sortField, newValue);
    }

    render(){
        if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;

        if(this.state.loading) return <div className="path-summary">
            <img style={{marginLeft:"auto",marginRight:"auto",width:"200px",display:"block"}} src="/assets/images/Spinner-1s-200px.gif"/>Loading...
        </div>;

        /*TODO: add in jschart and put a horizontal bar of the filetypes breakdown*/
        if(this.state.hasLoaded) return <div className="browse-path-summary">
            <div style={{width: "80%", display: "inline-block"}}>
                <p className="centered"><FontAwesomeIcon style={{marginRight: "0.5em"}} icon="hdd"/>{this.props.collectionName}</p>
                <p className="centered" style={{marginTop: "0.1em"}}><FontAwesomeIcon icon="folder" style={{marginRight: "0.5em", display: this.props.path ? "inline":"none"}}/>{this.props.path ? this.props.path : ""}</p>
            <p>
                Total of {this.state.totalHits} items occupying <BytesFormatter value={this.state.totalSize}/>
            </p>
            </div>

            <div style={{width: "18%", display: "inline-block"}}>
                    <label htmlFor="sort-field-selector" style={{marginRight: "0.4em"}}>Sort by</label>
                    <select id="sort-field-selector" value={this.props.sortField} onChange={this.sortFieldChanged}>
                        <option value="path">Filename</option>
                        <option value="last_modified">Age</option>
                        <option value="size">Size</option>
                    </select>
                    <ReactRadioButtonGroup name="sort-order-selector"
                                           options={["Ascending","Descending"]}
                                           value={this.props.sortOrder}
                                           isStateful={false} onChange={this.sortOrderChanged}/>
            </div>
            <p style={{display: this.state.deletedCounts.hasOwnProperty("1") ? "inherit" : "none"}}>
                {
                    this.state.deletedCounts.hasOwnProperty("1") ? this.state.deletedCounts["1"] : 0
                } tracked items have been deleted
            </p>
        </div>;

        return <div><i>not loaded</i></div>
    }
}

export default BrowsePathSummary;