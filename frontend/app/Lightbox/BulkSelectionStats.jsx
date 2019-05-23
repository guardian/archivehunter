import React from "react"
import axios from "axios"
import PropTypes from "prop-types"
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import LoadingThrobber from "../common/LoadingThrobber.jsx";

class BulkSelectionStats extends React.Component {
    static propTypes = {
        bulkId: PropTypes.string,
        user: PropTypes.string.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            currentStats: null,
            lastError: null
        }
    }

    loadData(){
        if(this.props.bulkId){
            this.setState({loading: true, lastError: null, currentStats: null}, ()=>axios.get("/api/archive/bulkStatus/" + this.props.user + "/" + this.props.bulkId).then(
                response=>{
                    this.setState({loading: false, lastError: null, currentStats: response.data.entry})
                }).catch(err=>{
                    this.setState({loading: false, lastError: err})
            }))
        } else {
            this.setState({loading: false, currentStats: null, lastError: null})
        }
    }

    componentWillMount() {
        this.loadData();
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        if(prevProps.bulkId!==this.props.bulkId || prevProps.user!==this.props.user) this.loadData();
    }

    render() {
        return <p className="information">
            <LoadingThrobber show={this.state.loading} small={true} inline={true}/>
            {
                this.state.lastError ? <ErrorViewComponent error={this.state.lastError}/> : ""
            }
            {
                this.state.currentStats ? "Un-needed: " + this.state.currentStats.unneeded + " In progress: " + this.state.currentStats.inProgress + " Available: " + this.state.currentStats.available + " Not requested: " + this.state.currentStats.notRequested :
                    "Select a bulk to see restore stats"
            }
        </p>
    }
}

export default BulkSelectionStats;