import React from 'react';
import PropTypes from 'prop-types';
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import axios from 'axios';
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import BytesFormatter from "../common/BytesFormatter.jsx";

class BulkLightboxAdd extends React.Component {
    static propTypes = {
        path: PropTypes.string.isRequired,
        hideDotFiles:PropTypes.bool.isRequired,
        queryString: PropTypes.string,
        collection: PropTypes.string.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            quotaExceeded: false,
            quotaRequired: -1,
            quotaLevel: -1
        };

        this.triggerBulkLightboxing = this.triggerBulkLightboxing.bind(this);
    }

    makeSearchJson(){
        const pathToSearch = this.props.path ?
            this.props.path.endsWith("/") ? this.props.path.slice(0, this.props.path.length - 1) : this.props.path : null;

        return JSON.stringify({
            hideDotFiles: ! this.props.showDotFiles,
            q: this.props.queryString,
            collection: this.props.collectionName,
            path: pathToSearch
        })
    }

    triggerBulkLightboxing() {
        if(this.state.loading) return;
        this.setState({loading: true, lastError:null},
            ()=>axios.put("/api/lightbox/my/addFromSearch", this.makeSearchJson(),{headers:{"Content-Type":"application/json"}}).then(response=>{
                console.log(response.data);
                this.setState({loading:false});
            }).catch(err=>{
                if(err.response && err.response.status===413){
                    console.log(err.response.data);
                    this.setState({
                        loading: false,
                        quotaExceeded: true,
                        quotaRequired: err.response.data.requiredQuota,
                        quotaLevel: err.response.data.actualQuota
                    })
                } else {
                    this.setState({loading: false, lastError: err});
                }
            })
        )
    }

    render(){
        return <div className="centered" style={{marginTop: "0.1em", paddingLeft: "0.5em", display: this.props.path ? "inline":"none"}}>
            {
                this.state.lastError ? <ErrorViewComponent error={this.state.lastError}/> : ""
            }
            {
                this.state.quotaExceeded ? <span><FontAwesomeIcon icon="lightbulb" className="button-icon"/><p>Can't add as this would exceed your quota. You would need <BytesFormatter value={this.state.quotaRequired*1048576}/> but only have <BytesFormatter value={this.state.quotaLevel*1048576}/>.</p></span> : ""
            }
            {
                this.state.quotaExceeded ? "" : <span>{
                    <FontAwesomeIcon icon={this.state.loading ? "redo-alt" : "lightbulb"} className={this.state.loading ? "button-icon spin" : "button-icon"}/>
                }<a style={{cursor: "pointer"}} onClick={this.triggerBulkLightboxing}>Lightbox All</a></span>
            }

        </div>
    }
}

export default BulkLightboxAdd;