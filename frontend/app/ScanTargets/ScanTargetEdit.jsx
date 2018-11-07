import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from '../common/TimestampFormatter.jsx';
import TimeIntervalComponent from '../common/TimeIntervalComponent.jsx';
import axios from 'axios';

class ScanTargetEdit extends React.Component {
    constructor(props){
        super(props);

        const defaultValues = {
            bucketName: "",
            enabled: false,
            lastScanned: null,
            scanInterval: 7200,
            scanInProgress: false,
            lastError: ""
        };

        this.state = {
            entry: defaultValues,
            loading: false,
            error: null
        };

        this.updateBucketname = this.updateBucketname.bind(this);
        this.toggleEnabled = this.toggleEnabled.bind(this);
        this.formSubmit = this.formSubmit.bind(this);


    }

    loadData(idToLoad){
        this.setState({error:null, loading:true},
            ()=>axios.get("/api/scanTarget/" + encodeURIComponent(idToLoad))
                .then(response=> {
                    this.setState({loading: false, error: null, entry: response.data.entry})
                })
                .catch(err=>{
                    console.error(err);
                    this.setState({loading: false, error: err, entry: null})
                })
        );
    }

    componentWillMount(){
        const idToLoad = this.props.location.pathname.split('/').slice(-1)[0];
        console.log("going to load from id", idToLoad);

        if(idToLoad!=="new"){
            this.loadData(idToLoad)
        } else {

        }
    }

    updateBucketname(evt){
        const newEntry = Object.assign({bucketName: evt.target.value}, this.state.entry);
        this.setState({entry: newEntry});
    }

    toggleEnabled(evt){
        const newEntry = Object.assign({enabled: !this.state.entry.enabled}, this.state.entry);
        this.setState({entry: newEntry});
    }

    formSubmit(evt){

        evt.preventDefault();
    }

    render(){
        return <form onSubmit={this.formSubmit}>
            <h2>Edit scan target <img src="/assets/images/Spinner-1s-44px.svg" style={{display: this.state.loading ? "inline" : "none"}}/></h2>
            <table className="table">
                <tbody>
                <tr>
                    <td>Bucket name</td>
                    <td><input value={this.state.entry.bucketName} onChange={this.updateBucketname} style={{width:"95%"}}/></td>
                </tr>
                <tr>
                    <td>Enabled</td>
                    <td><input type="checkbox" checked={this.state.entry.enabled} onChange={this.toggleEnabled}/></td>
                </tr>
                <tr>
                    <td>Last Scanned</td>
                    <td><TimestampFormatter relative={true} value={this.state.entry.lastScanned}/></td>
                </tr>
                <tr>
                    <td>Scan Interval</td>
                    <td><TimeIntervalComponent editable={true} value={this.state.entry.scanInterval}/></td>
                </tr>
                <tr>
                    <td>Last Error</td>
                    <td><textarea contentEditable={false} value={this.state.entry.lastError ? this.state.entry.lastError : ""} style={{width: "85%", verticalAlign:"middle"}}/>
                        <button type="button" onClick={this.clearErrorLog} style={{marginLeft: "0.5em", verticalAlign: "middle"}}>Clear</button></td>
                </tr>
                </tbody>
            </table>
            <input type="submit" value="Save"/>
            <button type="button">Back</button>
        </form>
    }
}

export default ScanTargetEdit;